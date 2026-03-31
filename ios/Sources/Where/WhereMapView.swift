import SwiftUI
import MapKit
import Shared

struct WhereMapView: UIViewRepresentable {
    let users: [Shared.UserLocation]
    let ownUserId: String
    var zoomTarget: CLLocationCoordinate2D? = nil
    var onZoomConsumed: () -> Void = {}

    func makeUIView(context: Context) -> MKMapView {
        let mapView = MKMapView()
        mapView.delegate = context.coordinator
        mapView.showsUserLocation = false
        // Set initial world view; will zoom to actual location once user's location is available
        let initialRegion = MKCoordinateRegion(
            center: CLLocationCoordinate2D(latitude: 20, longitude: 0),
            span: MKCoordinateSpan(latitudeDelta: 180, longitudeDelta: 360)
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
                mapView.addAnnotation(UserAnnotation(user: user, isOwn: user.userId == ownUserId))
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

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, MKMapViewDelegate {
        var hasCentered = false
        func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
            guard let userAnnotation = annotation as? UserAnnotation else { return nil }
            let id = "pin"
            let view = mapView.dequeueReusableAnnotationView(withIdentifier: id) as? MKMarkerAnnotationView
                ?? MKMarkerAnnotationView(annotation: annotation, reuseIdentifier: id)
            view.annotation = annotation
            view.markerTintColor = userAnnotation.isOwn ? .systemBlue : .systemRed
            view.glyphText = userAnnotation.isOwn ? "Me" : nil
            view.canShowCallout = true
            return view
        }
    }
}

final class UserAnnotation: NSObject, MKAnnotation {
    let userId: String
    @objc dynamic var coordinate: CLLocationCoordinate2D
    let title: String?
    let isOwn: Bool

    init(user: Shared.UserLocation, isOwn: Bool) {
        self.userId = user.userId
        self.coordinate = CLLocationCoordinate2D(latitude: user.lat, longitude: user.lng)
        self.title = isOwn ? "You" : String(user.userId.prefix(8))
        self.isOwn = isOwn
    }
}
