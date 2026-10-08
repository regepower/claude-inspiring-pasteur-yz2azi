# DualFiles

Dual-pane file manager for Android, portrait only. Four pages, swipe left/right:

1. folder tree (source) → 2. files (source) → 3. files (target) → 4. folder tree (target)

- Tap a row = open (folder in the list, file in its default app). Mark only with the check box on the left.
- Icon of a file = icon of the app that opens it.
- Hold a row = menu: open with / copy / move / delete (to the other side).
- Two fingers dragging sideways = scroll all long names; icons and check boxes stay. One finger swipes the pages.
- Tap a folder in a tree = select it and jump to its file page.
- Back = one folder up in the visible pane. At the top level, a second back press exits.

Kotlin, no dependencies. Built by GitHub Actions (`Actions → run → Artifacts → DualFiles-release`).
Needs "all files access" (granted on first start).

Large folders: directories are read in a background thread, each file gets one attribute call, folder item counts are filled in afterwards (shows "…" until then).
