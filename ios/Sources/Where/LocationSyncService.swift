import Foundation

struct UserLocationData: Codable, Identifiable {
    let userId: String
    let lat: Double
    let lng: Double
    let timestamp: Int64

    var id: String { userId }
}

private struct OutgoingMessage: Codable {
    let type: String
    let location: UserLocationData
}

private struct IncomingMessage: Codable {
    let type: String
    let users: [UserLocationData]?
}

enum ConnectionState {
    case disconnected, connecting, connected
}

@MainActor
final class LocationSyncService: ObservableObject {
    @Published var users: [UserLocationData] = []
    @Published var connectionState: ConnectionState = .disconnected

    private let userId: String
    private var webSocketTask: URLSessionWebSocketTask?
    private var receiveLoop: Task<Void, Never>?

    init(userId: String) {
        self.userId = userId
    }

    func connect() {
        connectionState = .connecting
        let urlString = "ws://localhost:8080/ws?userId=\(userId)"
        guard let url = URL(string: urlString) else { return }
        let task = URLSession.shared.webSocketTask(with: url)
        webSocketTask = task
        task.resume()
        startReceiving(task: task)
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
        let msg = OutgoingMessage(
            type: "location",
            location: UserLocationData(
                userId: userId,
                lat: lat,
                lng: lng,
                timestamp: Int64(Date().timeIntervalSince1970 * 1000)
            )
        )
        guard let data = try? JSONEncoder().encode(msg),
              let text = String(data: data, encoding: .utf8) else { return }
        task.send(.string(text)) { _ in }
        connectionState = .connected
    }

    private func startReceiving(task: URLSessionWebSocketTask) {
        receiveLoop = Task { [weak self] in
            guard let self else { return }
            while !Task.isCancelled {
                do {
                    let message = try await task.receive()
                    await MainActor.run { self.connectionState = .connected }
                    if case .string(let text) = message,
                       let data = text.data(using: .utf8),
                       let msg = try? JSONDecoder().decode(IncomingMessage.self, from: data),
                       msg.type == "locations",
                       let users = msg.users {
                        await MainActor.run { self.users = users }
                    }
                } catch {
                    guard !Task.isCancelled else { break }
                    await MainActor.run { self.connectionState = .disconnected }
                    try? await Task.sleep(nanoseconds: 3_000_000_000)
                    await self.connect()
                    break
                }
            }
        }
    }
}
