---
# Design Tokens
colors:
  brand:
    primary: "#4DA828"    # App brand green (launcher/identity)
    secondary: "#34a853"  # Web brand green (Google Play/Buttons)
    link: "#007aff"       # Web/iOS system link blue
  app:
    active: "#1565C0"     # Location sharing active blue
    inactive: "#555555"   # Location sharing paused gray
  status:
    success: "#4CAF50"    # Connection OK green
    warning: "#FFA500"    # Connection warning orange
    error: "#F44336"      # Error/Inactive red
  surface:
    overlay: "rgba(0, 0, 0, 0.70)" # App HUD semi-transparent black
    background: "#FFFFFF"          # Standard background
    sheet: "#FFFFFF"               # Bottom sheets and drawers
    card-border: "#E0E0E0"         # Border for the web invite card
  text:
    on-overlay: "#FFFFFF" # White text on dark HUDs
    primary: "#1C1B1F"    # Material 3 / General Body (#222222 on web)
    secondary: "#49454F"  # Subtitles and captions (#555555 on web)
    muted: "#888888"      # Footer and metadata

typography:
  families:
    primary: "system-ui, -apple-system, BlinkMacSystemFont, Roboto, sans-serif"
    technical: "ui-monospace, 'SF Mono', 'Roboto Mono', monospace"
  sizes:
    xs: "10px"            # App metadata
    sm: "12px"            # Captions / Nav links
    md: "16px"            # Body text
    lg: "22px"            # Section titles
    xl: "2.5rem"          # Invite card header (approx 40px)
  weights:
    regular: 400
    medium: 500
    semibold: 600
    bold: 700
    black: 800

spacing:
  base: "4px"
  xs: "4px"
  sm: "8px"
  md: "12px"
  lg: "16px"
  xl: "24px"
  xxl: "48px"
  invite-padding: "40px 32px"

radii:
  sm: "4px"               # Subtle rounding
  md: "10px"              # Store links (rounded-rect)
  lg: "16px"              # Invite cards
  xl: "24px"              # Bottom drawers (Web/iOS)
  full: "9999px"          # Circular indicators

motion:
  standard: "300ms ease-in-out"
  drawer: "400ms cubic-bezier(0.16, 1, 0.3, 1)"

elevation:
  none: "0px"
  hud: "0px 2px 4px rgba(0, 0, 0, 0.3)"
  drawer: "0px -10px 25px rgba(0, 0, 0, 0.1)"
---

# Design System: Where

## Look & Feel
"Where" balances a **technical, privacy-first core** with a **warm, approachable interface**. The design language is consistent across mobile applications and the web, emphasizing clarity, transparency, and user trust.

### 1. Unified Brand Identity
The brand is anchored by a vibrant green (`colors.brand.primary`), signaling safety and "go" (active connection). While the mobile app uses a slightly more muted lime-green for its launcher, the website leans into a bold, trusted green for its primary call-to-action buttons.

### 2. Functional Transparency (The HUD Aesthetic)
In the mobile app, the UI prioritizes the map, using "Glass-Dark" overlays (`surface.overlay`). This aesthetic creates an immersive, "mission-control" feel where controls feel light and secondary to the primary data (location). On the web, this transparency is echoed through the use of `backdrop-filter` on bottom drawers, blurring the background to maintain context without clutter.

### 3. Safety by Design (The Invite Experience)
The web invite page is a study in **minimalist reassurance**. It uses a single, centered `invite-card` with generous padding (`spacing.invite-padding`) and a soft border. 
- **Security as UX:** The page layout is designed around a single-use "Universal Link" pattern. If the app is installed, the OS intercepts the link; if not, the web fallback provides a clear, high-contrast path to the store.
- **Privacy Preservation:** The UI deliberately avoids processing cryptographic keys in the browser's JavaScript context, reflecting the "zero-trust" design of the entire system.

### 4. Cryptographic Sincerity
The application intentionally surfaces "raw" data like safety numbers and precise timestamps using monospaced typography (`typography.families.technical`). This isn't just a stylistic choice; it's a "proof of work" that communicates the underlying end-to-end encryption to the user through visual cues.

### 5. Native & Familiar Patterns
"Where" respects platform conventions to build immediate familiarity:
- **iOS/Web:** Uses `system-ui` and standard blue links (`colors.brand.link`).
- **Android:** Adheres to Material 3 color palettes and spacing logic.
- **Interaction:** Both mobile and web utilize large-radius bottom sheets (`radii.xl`) for complex interactions, providing a consistent "slide-up" mental model for settings and friend management.

### 6. High-Signal Status
Color is used sparingly but strictly for status:
- **Vibrant Blue:** Indicates an active, broadcasting state.
- **Traffic Light System:** Green/Orange/Red signals connection health and data staleness with zero ambiguity.
- **Muted Grays:** Used for "off" or "paused" states, ensuring they recede visually compared to active participants.
