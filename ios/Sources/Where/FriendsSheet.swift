import SwiftUI

struct FriendsSheet: View {
    @ObservedObject var store: FriendsStore
    var onZoomTo: (String) -> Void = { _ in }
    @State private var newId: String = ""
    @State private var showCopied = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        Text(UserIdentity.userId)
                            .font(.system(.caption, design: .monospaced))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                        Spacer()
                        Button(showCopied ? "Copied!" : "Copy") {
                            UIPasteboard.general.string = UserIdentity.userId
                            showCopied = true
                            Task {
                                try? await Task.sleep(nanoseconds: 2_000_000_000)
                                showCopied = false
                            }
                        }
                        .font(.caption)
                        .buttonStyle(.bordered)
                    }
                } header: {
                    Text("Your ID — share this with friends")
                } footer: {
                    Text("Anyone who adds your ID will see your location on their map.")
                        .font(.caption2)
                }

                Section("Add a friend") {
                    HStack {
                        TextField("Paste their ID", text: $newId)
                            .font(.system(.caption, design: .monospaced))
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                        Button("Add") {
                            store.add(id: newId)
                            newId = ""
                        }
                        .disabled(newId.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                }

                if !store.friendIds.isEmpty {
                    Section("Friends") {
                        ForEach(Array(store.friendIds), id: \.self) { id in
                            Button {
                                onZoomTo(id)
                                dismiss()
                            } label: {
                                HStack {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(id.prefix(8))
                                            .font(.system(.body, design: .monospaced))
                                        Text(id)
                                            .font(.system(.caption2, design: .monospaced))
                                            .foregroundStyle(.secondary)
                                            .lineLimit(1)
                                            .truncationMode(.middle)
                                    }
                                    Spacer()
                                    Image(systemName: "location.fill")
                                        .foregroundStyle(.secondary)
                                        .font(.caption)
                                }
                                .foregroundStyle(.primary)
                            }
                        }
                        .onDelete { offsets in
                            let ids = Array(store.friendIds)
                            offsets.forEach { store.remove(id: ids[$0]) }
                        }
                    }
                }
            }
            .navigationTitle("Friends")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}
