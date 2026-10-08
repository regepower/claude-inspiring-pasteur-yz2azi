# DualFiles

Dual-pane file manager for Android, portrait only. Four pages, swipe left/right:

1. folder tree (source) → 2. files (source) → 3. files (target) → 4. folder tree (target)

- Tap a row = mark/unmark. Double tap = open (folder, or file in its default app).
- Icon of a file = icon of the app that opens it.
- Hold a row and drag sideways = scroll a long name. Hold and release = menu: copy / move / delete to the other side.
- Tap a folder in a tree = select it and jump to its file page.

Kotlin, no dependencies. Built by GitHub Actions (`Actions → run → Artifacts → DualFiles-release`).
Needs "all files access" (granted on first start).
