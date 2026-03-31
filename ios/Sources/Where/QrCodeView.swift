import CoreImage
import CoreImage.CIFilterBuiltins
import Shared
import SwiftUI

struct QrCodeView: View {
    let content: String
    var size: CGFloat = 240

    var body: some View {
        if let image = generateQrImage(content) {
            Image(uiImage: image)
                .interpolation(.none)
                .resizable()
                .scaledToFit()
                .frame(width: size, height: size)
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
    let onDismiss: () -> Void

    @State private var showShareSheet = false

    private var qrUrl: String { qrPayloadToUrl(qrPayload) }

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                Text("Invite a Friend")
                    .font(.title2)
                    .bold()

                Text("Have them scan this QR code or share the link.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)

                QrCodeView(content: qrUrl)
                    .padding()
                    .background(Color.white)
                    .cornerRadius(12)
                    .shadow(radius: 4)

                HStack(spacing: 12) {
                    Button("Cancel", role: .cancel) { onDismiss() }
                        .buttonStyle(.bordered)
                    Button("Share Link") { showShareSheet = true }
                        .buttonStyle(.borderedProminent)
                }
            }
            .padding(32)
        }
        .sheet(isPresented: $showShareSheet) {
            ShareSheet(items: [qrUrl])
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
