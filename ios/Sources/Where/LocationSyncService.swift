import Foundation
import Shared

enum ConnectionState {
    case disconnected, connecting, connected
}

@MainActor
final class LocationSyncService: ObservableObject {
    @Published var users: [UserLocation] = []
    @Published var connectionState: ConnectionState = .disconnected

    private static let serverWsBaseUrl = "ws://localhost:8080/ws"

    private let userId: String
    private var webSocketTask: URLSessionWebSocketTask?
    private var receiveLoop: Task<Void, Never>?

    init(userId: String) {
        self.userId = userId
    }

    func connect() {
        // Cancel any existing connection before creating a new one to prevent task leakage.
        receiveLoop?.cancel()
        receiveLoop = nil
        webSocketTask?.cancel(with: .goingAway, reason: nil)
        webSocketTask = nil

        connectionState = .connecting
        let urlString = "\(Self.serverWsBaseUrl)?userId=\(userId)"
        guard let url = URL(string: urlString) else { return }
        let task = URLSession.shared.webSocketTask(with: url)
        webSocketTask = task
        task.resume()
        startReceiving(from: task)
    }

    func disconnect() {
        receiveLoop?.cancel()
        receiveLoop = nil
        webSocketTask?.cancel(with: .goingAway, reason: nil)
        webSocketTask = nil
        connectionState = .disconnected
    }

    func sendLocation(lat: Double, lng: Double) {
        guard let task = webSocketTask else { return }
        let timestamp = Int64(Date().timeIntervalSince1970 * 1000)
        let text = LocationMessageCodec.shared.encodeLocationUpdate(
            userId: userId, lat: lat, lng: lng, timestamp: timestamp
        )
        task.send(.string(text)) { _ in }
        connectionState = .connected
    }

    func sendLocationRemove() {
        guard let task = webSocketTask else { return }
        let text = LocationMessageCodec.shared.encodeLocationRemove()
        task.send(.string(text)) { _ in }
    }

    private func startReceiving(from initialTask: URLSessionWebSocketTask) {
        receiveLoop = Task { [weak self] in
            guard let self else { return }
            var wsTask = initialTask
            while !Task.isCancelled {
                do {
                    let message = try await wsTask.receive()
                    connectionState = .connected
                    if case .string(let text) = message,
                       let users = LocationMessageCodec.shared.decodeUsers(text: text) {
                        self.users = users as! [UserLocation]
                    }
                } catch {
                    guard !Task.isCancelled else { break }
                    connectionState = .disconnected
                    try? await Task.sleep(nanoseconds: 3_000_000_000)
                    guard !Task.isCancelled else { break }
                    let urlString = "\(Self.serverWsBaseUrl)?userId=\(userId)"
                    guard let url = URL(string: urlString) else { break }
                    let newTask = URLSession.shared.webSocketTask(with: url)
                    webSocketTask = newTask
                    connectionState = .connecting
                    newTask.resume()
                    wsTask = newTask
                }
            }
        }
    }
}
