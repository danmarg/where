import SwiftUI
import MapKit
import Shared

struct WhereMapView: UIViewRepresentable {
    let users: [Shared.UserLocation]
    let friends: [Shared.FriendEntry]
    let ownUserId: String
    var zoomTarget: CLLocationCoordinate2D? = nil
    var onZoomConsumed: () -> Void = {}
    var onSelectFriend: (String) -> Void = { _ in }

    func makeUIView(context: Context) -> MKMapView {
        let mapView = MKMapView()
        mapView.delegate = context.coordinator
        mapView.showsUserLocation = false
        // Set initial region to San Francisco; will zoom to actual location once user's location is available
        let initialRegion = MKCoordinateRegion(
            center: CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194),
            span: MKCoordinateSpan(latitudeDelta: 1.0, longitudeDelta: 1.0)
        )
        mapView.setRegion(initialRegion, animated: false)
        return mapView
    }

    func updateUIView(_ mapView: MKMapView, context: Context) {
        let existing = mapView.annotations.compactMap { $0 as? UserAnnotation }
        let existingById = Dictionary(uniqueKeysWithValues: existing.map { ($0.userId, $0) })
        let newById = Dictionary(uniqueKeysWithValues: users.map { ($0.userId, $0) })

        // Remove stale annotations
        let toRemove = existing.filter { newById[$0.userId] == nil }
        mapView.removeAnnotations(toRemove)

        // Update existing or add new annotations
        for user in users {
            if let pin = existingById[user.userId] {
                pin.coordinate = CLLocationCoordinate2D(latitude: user.lat, longitude: user.lng)
            } else {
                let friend = friends.first { $0.id == user.userId }
                let friendName = friend?.name ?? String(user.userId.prefix(8))
                mapView.addAnnotation(UserAnnotation(user: user, friendName: friendName, isOwn: user.userId == ownUserId))
            }
        }

        // Zoom to friend if requested, then clear the target so it doesn't re-trigger.
        if let target = zoomTarget {
            let region = MKCoordinateRegion(center: target, latitudinalMeters: 1000, longitudinalMeters: 1000)
            mapView.setRegion(region, animated: true)
            DispatchQueue.main.async { onZoomConsumed() }
            return
        }

        // Auto-center on own location the first time only
        if !context.coordinator.hasCentered,
           let own = users.first(where: { $0.userId == ownUserId }) {
            let coord = CLLocationCoordinate2D(latitude: own.lat, longitude: own.lng)
            let region = MKCoordinateRegion(center: coord, latitudinalMeters: 5000, longitudinalMeters: 5000)
            mapView.setRegion(region, animated: true)
            context.coordinator.hasCentered = true
        }
    }

    func makeCoordinator() -> Coordinator {
        let coordinator = Coordinator()
        coordinator.parentView = self
        return coordinator
    }

    final class Coordinator: NSObject, MKMapViewDelegate {
        var hasCentered = false
        var parentView: WhereMapView?

        func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
            guard let userAnnotation = annotation as? UserAnnotation else { return nil }
            let id = "pin"
            let view = mapView.dequeueReusableAnnotationView(withIdentifier: id) as? MKMarkerAnnotationView
                ?? MKMarkerAnnotationView(annotation: annotation, reuseIdentifier: id)
            view.annotation = annotation
            view.markerTintColor = userAnnotation.isOwn ? .systemBlue : .systemRed
            view.glyphText = userAnnotation.isOwn ? "Me" : nil
            view.canShowCallout = true

            if !userAnnotation.isOwn {
                let detailButton = UIButton(type: .detailDisclosure)
                view.rightCalloutAccessoryView = detailButton
            }

            return view
        }

        func mapView(_ mapView: MKMapView, annotationView view: MKAnnotationView, calloutAccessoryControlTapped control: UIControl) {
            guard let userAnnotation = view.annotation as? UserAnnotation else { return }
            parentView?.onSelectFriend(userAnnotation.userId)
        }
    }
}

final class UserAnnotation: NSObject, MKAnnotation {
    let userId: String
    @objc dynamic var coordinate: CLLocationCoordinate2D
    let title: String?
    let subtitle: String?
    let isOwn: Bool

    init(user: Shared.UserLocation, friendName: String, isOwn: Bool) {
        self.userId = user.userId
        self.coordinate = CLLocationCoordinate2D(latitude: user.lat, longitude: user.lng)
        self.title = isOwn ? "You" : friendName
        self.subtitle = isOwn ? nil : user.userId.prefix(8).description
        self.isOwn = isOwn
    }
}
