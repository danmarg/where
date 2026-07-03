import Shared
import SwiftUI

private struct LibraryLicense: Identifiable {
    let id = UUID()
    let name: String
    let copyright: String
    let spdx: String
}

private let licenses: [LibraryLicense] = [
    .init(name: "libsodium",                              copyright: "Copyright © 2013–2024 Frank Denis",            spdx: "ISC"),
    .init(name: "multiplatform-crypto-libsodium-bindings", copyright: "Copyright © 2020 Ugljesa Jovanovic",          spdx: "Apache-2.0"),
    .init(name: "Ktor",                                   copyright: "Copyright © JetBrains s.r.o.",                 spdx: "Apache-2.0"),
    .init(name: "Kotlin Coroutines / Serialization",      copyright: "Copyright © JetBrains s.r.o.",                 spdx: "Apache-2.0"),
    .init(name: "SQLDelight",                             copyright: "Copyright © 2016 Square, Inc.",                spdx: "Apache-2.0"),
    .init(name: "MOKO Resources",                         copyright: "Copyright © 2019 IceRock Development",         spdx: "Apache-2.0"),
]

struct AcknowledgementsView: View {
    var body: some View {
        List(licenses) { lib in
            VStack(alignment: .leading, spacing: 2) {
                Text(lib.name)
                    .font(.subheadline)
                    .fontWeight(.medium)
                Text(lib.copyright)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(lib.spdx)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(.vertical, 4)
        }
        .navigationTitle(MR.strings().open_source_licenses.localized())
        #if !os(macOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
    }
}
