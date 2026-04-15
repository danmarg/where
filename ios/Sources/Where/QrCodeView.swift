import CoreImage
import CoreImage.CIFilterBuiltins
import Shared
import SwiftUI

struct QrCodeView: View {
    let content: String
    var size: CGFloat = 240

    @State private var cachedImage: UIImage? = nil

    var body: some View {
        Group {
            if let image = cachedImage {
                Image(uiImage: image)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: size, height: size)
            }
        }
        .onAppear {
            if cachedImage == nil {
                cachedImage = generateQrImage(content)
            }
        }
        .onChange(of: content) { oldValue, newValue in
            if oldValue != newValue {
                cachedImage = generateQrImage(newValue)
            }
        }
    }

    private func generateQrImage(_ string: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 6, y: 6))
        let context = CIContext()
        guard let cgImage = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}

struct InviteSheet: View {
    let qrPayload: Shared.QrPayload
    @Binding var displayName: String
    let onDismiss: () -> Void

    @State private var showShareSheet = false
    @State private var cachedQrUrl: String = ""

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                Text(MR.strings().invite_a_friend.localized())
                    .font(.title2)
                    .bold()

                Text(MR.strings().invite_message.localized())
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)

                TextField(MR.strings().your_name.localized(), text: $displayName)
                    .textFieldStyle(.roundedBorder)
                    .multilineTextAlignment(.center)

                QrCodeView(content: cachedQrUrl)
                    .padding()
                    .background(Color.white)
                    .cornerRadius(12)
                    .shadow(radius: 4)

                HStack(spacing: 12) {
                    Button(MR.strings().cancel.localized(), role: .cancel) { onDismiss() }
                        .buttonStyle(.bordered)
                    Button(MR.strings().share_link.localized()) { showShareSheet = true }
                        .buttonStyle(.borderedProminent)
                        .disabled(cachedQrUrl.isEmpty)
                }
            }
            .padding(32)
        }
        .onAppear {
            cachedQrUrl = qrPayload.toUrl()
        }
        .onChange(of: qrPayload) { oldValue, newValue in
            cachedQrUrl = newValue.toUrl()
        }
        .sheet(isPresented: $showShareSheet) {
            ShareSheet(items: [cachedQrUrl])
        }
    }
}

private struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

// Make qrPayloadToUrl accessible here (defined in LocationSyncService.swift, same module)
