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

        // Update existing or add new friend annotations
        for user in users {
            let coord = CLLocationCoordinate2D(latitude: user.lat, longitude: user.lng)
            let friend = friends.first { $0.id == user.userId }
            let friendName = friend?.name ?? String(user.userId.prefix(8))
            let lastPing = friendLastPing[user.userId]
            if let pin = existingById[user.userId] {
                pin.trueCoordinate = coord
                pin.title = friendName
                pin.subtitle = timeAgoString(lastPing)
            } else {
                mapView.addAnnotation(UserAnnotation(userId: user.userId, coordinate: coord, friendName: friendName, lastPing: lastPing))
            }
        }
        applyScreenOffsets(mapView)

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

    private func applyScreenOffsets(_ mapView: MKMapView) {
        let threshold: CGFloat = 44
        let annotations = mapView.annotations.compactMap { $0 as? UserAnnotation }

        struct Pin { var ann: UserAnnotation?; var pt: CGPoint; let fixed: Bool }
        var pins: [Pin] = []
        if let own = ownLocation {
            pins.append(Pin(ann: nil, pt: mapView.convert(own, toPointTo: mapView), fixed: true))
        }
        for ann in annotations {
            pins.append(Pin(ann: ann, pt: mapView.convert(ann.trueCoordinate, toPointTo: mapView), fixed: ann.isOwn))
        }

        for _ in 0..<5 {
            for i in pins.indices {
                guard !pins[i].fixed else { continue }
                for j in pins.indices where j != i {
                    let dx = pins[i].pt.x - pins[j].pt.x
                    let dy = pins[i].pt.y - pins[j].pt.y
                    var dist = sqrt(dx * dx + dy * dy)
                    guard dist < threshold else { continue }
                    if dist < 0.01 { dist = 0.01 }
                    let push = (threshold - dist) / (pins[j].fixed ? 1 : 2)
                    pins[i].pt.x += dx / dist * push
                    pins[i].pt.y += dy / dist * push
                    if !pins[j].fixed {
                        pins[j].pt.x -= dx / dist * push
                        pins[j].pt.y -= dy / dist * push
                    }
                }
            }
        }

        for pin in pins {
            guard let ann = pin.ann, !ann.isOwn else { continue }
            ann.coordinate = mapView.convert(pin.pt, toCoordinateFrom: mapView)
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
            parentView?.applyScreenOffsets(mapView)
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
            view.canShowCallout = true

            let detailButton = UIButton(type: .detailDisclosure)
            view.rightCalloutAccessoryView = detailButton

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
    var trueCoordinate: CLLocationCoordinate2D  // server-reported; never modified by spread
    @objc dynamic var coordinate: CLLocationCoordinate2D  // displayed (may be offset by spread)
    var heading: Double?
    var title: String?
    var subtitle: String?
    let isOwn: Bool

    init(userId: String, coordinate: CLLocationCoordinate2D, friendName: String, lastPing: Date?) {
        self.userId = userId
        self.trueCoordinate = coordinate
        self.coordinate = coordinate
        self.heading = nil
        self.title = friendName
        self.subtitle = timeAgoString(lastPing)
        self.isOwn = false
    }

    init(ownCoordinate: CLLocationCoordinate2D, heading: Double? = nil) {
        self.userId = "__own__"
        self.trueCoordinate = ownCoordinate
        self.coordinate = ownCoordinate
        self.heading = heading
        self.title = MR.strings().you.localized()
        self.subtitle = nil
        self.isOwn = true
    }
}

final class OwnLocationView: MKAnnotationView {
    private let beamLayer = CAGradientLayer()
    private let beamMask = CAShapeLayer()
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
        // Axial gradient: opaque at dot center, transparent at cone tip.
        // Mask clips it to a ~35° cone pointing up (before rotation).
        beamLayer.frame = bounds
        beamLayer.colors = [UIColor.systemBlue.withAlphaComponent(0).cgColor,
                            UIColor.systemBlue.withAlphaComponent(0.45).cgColor]
        beamLayer.startPoint = CGPoint(x: 0.5, y: 0)
        beamLayer.endPoint = CGPoint(x: 0.5, y: 0.5)
        beamLayer.isHidden = true

        let c = CGPoint(x: 32, y: 32)
        let path = UIBezierPath()
        path.move(to: c)
        // ~17.5° half-angle → 10 px half-width at 32 px distance
        path.addLine(to: CGPoint(x: c.x - 10, y: 0))
        path.addLine(to: CGPoint(x: c.x + 10, y: 0))
        path.close()
        beamMask.path = path.cgPath
        beamLayer.mask = beamMask

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
