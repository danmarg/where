import SwiftUI
import VisionKit
import Shared

struct QrScannerView: View {
    let onScan: (String) -> Void
    let onDismiss: () -> Void

    var body: some View {
        #if targetEnvironment(simulator)
        SimulatorQrScannerView(onScan: onScan, onDismiss: onDismiss)
        #else
        DataScannerRepresentable(onScan: onScan, onDismiss: onDismiss)
        #endif
    }
}

struct SimulatorQrScannerView: View {
    let onScan: (String) -> Void
    let onDismiss: () -> Void
    @State private var manualUrl: String = "https://where.af0.net/invite#..."

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Image(systemName: "qrcode.viewfinder")
                    .font(.system(size: 80))
                    .foregroundStyle(.secondary)
                
                Text(MR.strings().qr_scanner_simulator.localized())
                    .font(.headline)
                
                Text(MR.strings().camera_unavailable_emulator.localized())
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)

                TextField("https://where.af0.net/invite#...", text: $manualUrl)
                    .textFieldStyle(.roundedBorder)
                    .padding(.horizontal)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)

                Button(MR.strings().simulate_scan.localized()) {
                    onScan(manualUrl)
                }
                .buttonStyle(.borderedProminent)
                .disabled(manualUrl.isEmpty)

                Spacer()
            }
            .padding(.top, 40)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(MR.strings().cancel.localized()) {
                        onDismiss()
                    }
                }
            }
        }
    }
}

struct DataScannerRepresentable: UIViewControllerRepresentable {
    let onScan: (String) -> Void
    let onDismiss: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onScan: onScan, onDismiss: onDismiss) }

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let vc = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])],
            qualityLevel: .balanced,
            recognizesMultipleItems: false,
            isHighFrameRateTrackingEnabled: false,
            isHighlightingEnabled: true
        )
        vc.delegate = context.coordinator
        try? vc.startScanning()
        return vc
    }

    func updateUIViewController(_ uiViewController: DataScannerViewController, context: Context) {}

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onScan: (String) -> Void
        let onDismiss: () -> Void

        init(onScan: @escaping (String) -> Void, onDismiss: @escaping () -> Void) {
            self.onScan = onScan
            self.onDismiss = onDismiss
        }

        func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            for item in addedItems {
                if case .barcode(let barcode) = item, let payload = barcode.payloadStringValue {
                    dataScanner.stopScanning()
                    onScan(payload)
                    return
                }
            }
        }
    }
}
