# Design System Specification

## 1. Overview & Creative North Star: "The Precision Luminary"
This design system rejects the "boxed-in" nature of traditional enterprise dashboards. Instead of a rigid grid of outlines, we embrace **The Precision Luminary**—an aesthetic defined by atmospheric depth, high-end editorial typography, and surgical use of color. 

The goal is to move beyond "standard UI" by utilizing intentional asymmetry and tonal shifts to guide the eye. We treat the interface as a digital canvas where white space is not "empty," but a deliberate structural element. By removing traditional borders and relying on layered surfaces, we create a sense of organized calm that feels premium, bespoke, and authoritative.

## 2. Colors & Surface Philosophy
The palette utilizes a sophisticated range of cool grays and vibrant cyan accents to create a "bright-tech" atmosphere.

### The "No-Line" Rule
**Strict Prohibition:** 1px solid borders are forbidden for sectioning or layout containment. 
Boundaries must be defined through background color shifts. A `surface-container-low` section sitting on a `surface` background creates a clear but soft boundary that feels integrated rather than partitioned.

### Surface Hierarchy & Nesting
Treat the UI as a series of physical layers. Use the `surface-container` tiers to create depth:
*   **Base:** `surface` (#f7f9fc) serves as the canvas.
*   **Primary Work Areas:** `surface-container-low` (#f2f4f7) for large sidebar or grouping areas.
*   **Actionable Cards:** `surface-container-lowest` (#ffffff) for high-priority modules to create a "lifted" appearance.
*   **Nested Elements:** Use `surface-container-high` (#e6e8eb) for small internal utility components like search bars or tags within a card.

### The "Glass & Gradient" Rule
To elevate the primary action points, move beyond flat fills. 
*   **Signature Gradients:** Use a linear gradient from `primary` (#00687a) to `primary-container` (#06b6d4) for high-impact CTAs or "Hero" data visualizations.
*   **Glassmorphism:** For floating overlays (modals, dropdowns), use `surface-container-lowest` with an 80% opacity and a `24px` backdrop-blur. This ensures the dashboard feels interconnected and multi-dimensional.

## 3. Typography: Editorial Authority
We utilize a dual-typeface system to balance character with utility.

*   **Display & Headlines (Manrope):** These are our "Editorial" anchors. Use `display-lg` and `headline-md` to create clear entry points for the eye. The geometric nature of Manrope provides a modern, high-end tech feel. Use tighter letter-spacing (-0.02em) for large headlines to increase "density" and authority.
*   **Body & Titles (Inter):** Inter is our workhorse. It provides maximum legibility for dense enterprise data. Use `title-sm` for card headers and `body-md` for general data points.
*   **Intentional Contrast:** Pair a large `headline-sm` (Manrope) with a small, all-caps `label-md` (Inter) in `on-surface-variant` to create a sophisticated, magazine-style hierarchy.

## 4. Elevation & Depth
Depth is achieved through **Tonal Layering** rather than structural lines.

### The Layering Principle
Hierarchy is defined by "stacking." For example, a `surface-container-lowest` card placed on a `surface-container-low` background creates an immediate visual priority without the need for a shadow.

### Ambient Shadows
When a "floating" effect is required for modals or active states:
*   **Shadow Specs:** `0px 12px 32px rgba(25, 28, 30, 0.06)`
*   The shadow must be extra-diffused and low-opacity. The color is derived from `on-surface` (#191c1e) to ensure it looks like a natural, ambient obstruction of light.

### The "Ghost Border" Fallback
If an edge is absolutely required for accessibility (e.g., input fields):
*   Use the `outline-variant` (#bcc9cd) at **20% opacity**. 
*   **Never** use 100% opaque borders for decorative containment.

## 5. Components

### Buttons
*   **Primary:** Gradient fill (`primary` to `primary-container`). White text. `0.375rem` (md) corner radius.
*   **Secondary:** Fill with `secondary-container` (#bceaf7) and text in `on-secondary-container` (#3f6b76). No border.
*   **Tertiary:** No fill. `primary` text. Use a subtle `surface-container-high` background on hover.

### Input Fields
*   **Surface:** `surface-container-highest` (#e0e3e6) with a bottom-only "Ghost Border" in `primary` (#00687a) when focused. 
*   **Label:** Use `label-md` (Inter) positioned strictly above the field, never inside.

### Cards & Lists
*   **The No-Divider Rule:** Forbid the use of 1px divider lines. 
*   **Separation:** Separate list items using vertical white space (use the `16px` or `24px` spacing scale) or a subtle hover state shift to `surface-container-low`.
*   **Content Blocks:** Group related items by placing them on a shared `surface-container-lowest` card.

### Signature Component: The "Insight Rail"
An asymmetrical vertical panel on the right side of the dashboard using `surface-container-low` with a subtle glassmorphism effect. This rail houses secondary "glanceable" data, breaking the symmetry of the main grid and giving the UI a custom, editorial feel.

## 6. Do's and Don'ts

### Do:
*   **Do** use `primary-fixed` (#acedff) for subtle background highlights behind important icons.
*   **Do** allow elements to "bleed" or overlap slightly to create an intentional, non-templated layout.
*   **Do** prioritize white space over "filling the screen." If a section is empty, let it be.

### Don't:
*   **Don't** use a 1px solid border to separate the sidebar from the main content. Use a shift from `surface` to `surface-container-low`.
*   **Don't** use pure black for text. Always use `on-surface` (#191c1e) for better tonal harmony.
*   **Don't** use sharp corners. Stick to the `md` (0.375rem) and `lg` (0.5rem) roundedness scale to maintain a professional yet approachable aesthetic.