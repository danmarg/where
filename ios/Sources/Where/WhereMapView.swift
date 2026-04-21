import SwiftUI
import MapKit
import Shared

struct WhereMapView: UIViewRepresentable {
    let users: [Shared.UserLocation]
    let friends: [Shared.FriendEntry]
    let friendLastPing: [String: Date]
    var ownLocation: CLLocationCoordinate2D? = nil
    var ownHeading: Double? = nil
    var zoomTarget: CLLocationCoordinate2D? = nil
    var onZoomConsumed: () -> Void = {}
    var onSelectFriend: (String) -> Void = { _ in }

    private static let ownAnnotationId = "__own__"
func makeUIView(context: Context) -> MKMapView {
    let mapView = MKMapView()
    mapView.delegate = context.coordinator
    mapView.showsUserLocation = false

    let defaults = UserDefaults.standard
    let lat = defaults.double(forKey: "map_last_lat")
    let lng = defaults.double(forKey: "map_last_lng")
    let latDelta = defaults.double(forKey: "map_last_lat_delta")
    let lngDelta = defaults.double(forKey: "map_last_lng_delta")

    let initialRegion: MKCoordinateRegion
    if lat != 0 || lng != 0 {
        initialRegion = MKCoordinateRegion(
            center: CLLocationCoordinate2D(latitude: lat, longitude: lng),
            span: MKCoordinateSpan(latitudeDelta: latDelta == 0 ? 0.05 : latDelta, longitudeDelta: lngDelta == 0 ? 0.05 : lngDelta)
        )
    } else {
        // Default to something reasonable if no saved location
        initialRegion = MKCoordinateRegion(
            center: CLLocationCoordinate2D(latitude: 37.33, longitude: -122.03),
            span: MKCoordinateSpan(latitudeDelta: 0.1, longitudeDelta: 0.1)
        )
    }
    mapView.setRegion(initialRegion, animated: false)
    return mapView
}

func updateUIView(_ mapView: MKMapView, context: Context) {
        let existing = mapView.annotations.compactMap { $0 as? UserAnnotation }
        let existingById = Dictionary(uniqueKeysWithValues: existing.map { ($0.userId, $0) })
        let newFriendById = Dictionary(uniqueKeysWithValues: users.map { ($0.userId, $0) })

        // Remove stale annotations (own if location gone; friends if no longer present)
        let toRemove = existing.filter { ann in
            ann.isOwn ? ownLocation == nil : newFriendById[ann.userId] == nil
        }
        mapView.removeAnnotations(toRemove)

        // Update or add own pin
        if let own = ownLocation {
            if let existingOwn = existingById[Self.ownAnnotationId] {
                existingOwn.coordinate = own
                existingOwn.heading = ownHeading
                // Manually trigger view update for heading
                if let view = mapView.view(for: existingOwn) as? OwnLocationView {
                    view.update(heading: ownHeading, mapHeading: mapView.camera.heading)
                }
            } else {
                mapView.addAnnotation(UserAnnotation(ownCoordinate: own, heading: ownHeading))
            }
        }

        // Group friends by location to handle overlaps
        var locationGroups: [String: [Shared.UserLocation]] = [:]
        for user in users {
            let key = String(format: "%.6f,%.6f", user.lat, user.lng)
            locationGroups[key, default: []].append(user)
        }

        // Update existing or add new friend annotations
        for (_, group) in locationGroups {
            for (index, user) in group.enumerated() {
                var coord = CLLocationCoordinate2D(latitude: user.lat, longitude: user.lng)
                if group.count > 1 {
                    // Apply a small circular offset if multiple users are at the same spot
                    let angle = 2.0 * .pi * Double(index) / Double(group.count)
                    let radius = 0.00005 // Approx 5 meters at the equator
                    coord.latitude += radius * cos(angle)
                    coord.longitude += radius * sin(angle)
                }
                let friend = friends.first { $0.id == user.userId }
                let friendName = friend?.name ?? String(user.userId.prefix(8))
                let lastPing = friendLastPing[user.userId]
                if let pin = existingById[user.userId] {
                    pin.coordinate = coord
                    pin.title = friendName
                    pin.subtitle = timeAgoString(lastPing)
                } else {
                    mapView.addAnnotation(UserAnnotation(userId: user.userId, coordinate: coord, friendName: friendName, lastPing: lastPing))
                }
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
        if !context.coordinator.hasCentered, let own = ownLocation {
            let region = MKCoordinateRegion(center: own, latitudinalMeters: 5000, longitudinalMeters: 5000)
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
        private var lastClusterIds: [String] = []
        private var lastClusterIndex: Int = 0
        private var isCycling = false
        
        func mapView(_ mapView: MKMapView, regionDidChangeAnimated animated: Bool) {
            let region = mapView.region
            let defaults = UserDefaults.standard
            defaults.set(region.center.latitude, forKey: "map_last_lat")
            defaults.set(region.center.longitude, forKey: "map_last_lng")
            defaults.set(region.span.latitudeDelta, forKey: "map_last_lat_delta")
            defaults.set(region.span.longitudeDelta, forKey: "map_last_lng_delta")
            // Reapply heading beam when map is rotated so beam stays compass-correct
            if let ownAnn = mapView.annotations.compactMap({ $0 as? UserAnnotation }).first(where: { $0.isOwn }),
               let view = mapView.view(for: ownAnn) as? OwnLocationView {
                view.update(heading: ownAnn.heading, mapHeading: mapView.camera.heading)
            }
        }

        func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
            if annotation is MKUserLocation { return nil }
            
            guard let userAnnotation = annotation as? UserAnnotation else { return nil }
            let id = userAnnotation.isOwn ? "me" : "pin"
            
            if userAnnotation.isOwn {
                let view = mapView.dequeueReusableAnnotationView(withIdentifier: id) as? OwnLocationView
                    ?? OwnLocationView(annotation: annotation, reuseIdentifier: id)
                view.annotation = annotation
                view.update(heading: userAnnotation.heading, mapHeading: mapView.camera.heading)
                return view
            }

            let view = mapView.dequeueReusableAnnotationView(withIdentifier: id) as? MKMarkerAnnotationView
                ?? MKMarkerAnnotationView(annotation: annotation, reuseIdentifier: id)
            view.annotation = annotation
            view.markerTintColor = .systemRed
            view.glyphText = nil
            view.titleVisibility = .visible
            view.displayPriority = .required
            view.canShowCallout = true

            let detailButton = UIButton(type: .detailDisclosure)
            view.rightCalloutAccessoryView = detailButton

            return view
        }

        func mapView(_ mapView: MKMapView, didSelect view: MKAnnotationView) {
            guard !isCycling else { return }
            guard let tapped = view.annotation as? UserAnnotation, !tapped.isOwn else { return }

            let tappedPt = mapView.convert(tapped.coordinate, toPointTo: mapView)
            let cluster = mapView.annotations
                .compactMap { $0 as? UserAnnotation }
                .filter { !$0.isOwn }
                .filter {
                    let pt = mapView.convert($0.coordinate, toPointTo: mapView)
                    let dx = pt.x - tappedPt.x, dy = pt.y - tappedPt.y
                    return sqrt(dx * dx + dy * dy) < 44
                }
                .sorted { $0.userId < $1.userId }

            guard cluster.count > 1 else { lastClusterIds = []; return }

            let clusterIds = cluster.map(\.userId)
            if clusterIds == lastClusterIds {
                lastClusterIndex = (lastClusterIndex + 1) % cluster.count
            } else {
                // First tap at this cluster — show naturally-selected pin, record state
                lastClusterIds = clusterIds
                lastClusterIndex = cluster.firstIndex(where: { $0.userId == tapped.userId }) ?? 0
                return
            }

            let target = cluster[lastClusterIndex]
            guard target.userId != tapped.userId else { return }
            isCycling = true
            mapView.deselectAnnotation(tapped, animated: false)
            mapView.selectAnnotation(target, animated: false)
            isCycling = false
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
    var heading: Double?
    var title: String?
    var subtitle: String?
    let isOwn: Bool

    init(userId: String, coordinate: CLLocationCoordinate2D, friendName: String, lastPing: Date?) {
        self.userId = userId
        self.coordinate = coordinate
        self.heading = nil
        self.title = friendName
        self.subtitle = timeAgoString(lastPing)
        self.isOwn = false
    }

    init(ownCoordinate: CLLocationCoordinate2D, heading: Double? = nil) {
        self.userId = "__own__"
        self.coordinate = ownCoordinate
        self.heading = heading
        self.title = MR.strings().you.localized()
        self.subtitle = nil
        self.isOwn = true
    }
}

final class OwnLocationView: MKAnnotationView {
    private let beamLayer = CAGradientLayer()
    private let dotBorderLayer = CAShapeLayer()
    private let dotLayer = CAShapeLayer()

    override init(annotation: MKAnnotation?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        frame = CGRect(x: 0, y: 0, width: 64, height: 64)
        centerOffset = .zero
        setupLayers()
    }

    required init?(coder: NSCoder) { fatalError() }

    private func setupLayers() {
        let c = CGPoint(x: 32, y: 32)

        // Gradient: blue at the dot (center), fading to transparent at the far end (top)
        beamLayer.frame = bounds
        beamLayer.colors = [UIColor.systemBlue.withAlphaComponent(0.45).cgColor, UIColor.clear.cgColor]
        beamLayer.startPoint = CGPoint(x: 0.5, y: 0.5)
        beamLayer.endPoint   = CGPoint(x: 0.5, y: 0.0)
        beamLayer.isHidden = true

        // Narrow cone mask: tip at the dot (center), base fanning out (~35° half-angle)
        let coneMask = CAShapeLayer()
        let path = UIBezierPath()
        path.move(to: c)                                    // tip at dot
        path.addLine(to: CGPoint(x: c.x - 10, y: 0))       // base left at top
        path.addLine(to: CGPoint(x: c.x + 10, y: 0))       // base right at top
        path.close()
        coneMask.path = path.cgPath
        beamLayer.mask = coneMask

        layer.addSublayer(beamLayer)

        dotBorderLayer.frame = CGRect(x: 24, y: 24, width: 16, height: 16)
        dotBorderLayer.cornerRadius = 8
        dotBorderLayer.backgroundColor = UIColor.white.cgColor
        dotBorderLayer.shadowColor = UIColor.black.cgColor
        dotBorderLayer.shadowOpacity = 0.3
        dotBorderLayer.shadowOffset = CGSize(width: 0, height: 1)
        dotBorderLayer.shadowRadius = 2
        layer.addSublayer(dotBorderLayer)

        dotLayer.frame = CGRect(x: 26, y: 26, width: 12, height: 12)
        dotLayer.cornerRadius = 6
        dotLayer.backgroundColor = UIColor.systemBlue.cgColor
        layer.addSublayer(dotLayer)
    }

    func update(heading: Double?, mapHeading: Double) {
        if let h = heading {
            beamLayer.isHidden = false
            let radians = CGFloat((h - mapHeading) * .pi / 180.0)
            beamLayer.transform = CATransform3DMakeRotation(radians, 0, 0, 1)
        } else {
            beamLayer.isHidden = true
        }
    }
}
