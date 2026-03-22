import SwiftUI
import MapKit

struct WhereMapView: UIViewRepresentable {
    let users: [UserLocationData]
    let ownUserId: String

    func makeUIView(context: Context) -> MKMapView {
        let mapView = MKMapView()
        mapView.delegate = context.coordinator
        mapView.showsUserLocation = false
        return mapView
    }

    func updateUIView(_ mapView: MKMapView, context: Context) {
        // Remove old annotations
        mapView.removeAnnotations(mapView.annotations)

        // Add one annotation per user
        for user in users {
            let annotation = UserAnnotation(user: user, isOwn: user.userId == ownUserId)
            mapView.addAnnotation(annotation)
        }

        // Center on own location if present
        if let own = users.first(where: { $0.userId == ownUserId }) {
            let coord = CLLocationCoordinate2D(latitude: own.lat, longitude: own.lng)
            let region = MKCoordinateRegion(center: coord, latitudinalMeters: 5000, longitudinalMeters: 5000)
            mapView.setRegion(region, animated: true)
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, MKMapViewDelegate {
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
    let coordinate: CLLocationCoordinate2D
    let title: String?
    let isOwn: Bool

    init(user: UserLocationData, isOwn: Bool) {
        self.coordinate = CLLocationCoordinate2D(latitude: user.lat, longitude: user.lng)
        self.title = isOwn ? "You" : String(user.userId.prefix(8))
        self.isOwn = isOwn
    }
}
