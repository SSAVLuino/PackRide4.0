---
name: feedback-ui-overlays
description: Floating UI elements in PackRide4.0 must avoid the left sidebar and system status/navigation bars
metadata:
  type: feedback
---

When adding any floating UI element (FAB, chip, banner, dialog trigger) to `HomeScreen.kt` or other map-overlay screens, account for:

1. **The collapsible left sidebar** (`AppNavigation.kt`), which is an overlay `Box` aligned `CenterStart` with width `SIDEBAR_COLLAPSED_WIDTH = 64.dp` (collapsed) on top of the Home screen (Home has no start padding reserved for it). Anything aligned `BottomStart`/`TopStart`/`CenterStart` needs at least `start = 64.dp + 16.dp` padding to clear it.
2. **System status bar / navigation bar insets** — use `.windowInsetsPadding(WindowInsets.statusBars)` for top-aligned elements and `.windowInsetsPadding(WindowInsets.navigationBars)` for bottom-aligned elements.

**Why:** Repeated user feedback — the compass button ended up under the battery icon (fixed via `setCompassMargins`), and the debug-log FAB ended up under the sidebar (fixed via extra start padding). User explicitly asked ("teniamo a mente questi tipi per non fare sempre gli stessi errori") to remember this category of mistake.

**How to apply:** Before adding any new floating button/banner to HomeScreen, check its alignment against both the sidebar (left edge) and the relevant system bar insets, and pad accordingly from the start.
