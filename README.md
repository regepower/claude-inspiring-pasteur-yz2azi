# DualFiles

Dual-pane file manager for Android, portrait only. Four pages, swipe left/right:

1. folder tree (source) → 2. files (source) → 3. files (target) → 4. folder tree (target)

- Tap a row = open (folder in the list, file in its default app). Mark only with the check box on the left.
- Icon of a file = icon of the app that opens it.
- Hold a row = menu: open with / share / print / view as text or hex / rename / convert pictures to WebP / unpack ZIP or 7z / pack as ZIP / copy / move / delete (to the other side).
- Chip ⋮ above the list: new folder, favourite on/off (favourites at the top of the tree).
- Storage chip ("Intern ▾") in the band: internal, SD/USB, cloud folders from other apps (Google Drive …) and network servers (FTP, FTPS, WebDAV such as Nextcloud or a NAS; search in Wi-Fi, passwords encrypted with the Android keystore).
- Settings ⚙: file associations, Saf folders (Drive etc.), hidden files, previews, save/load configuration.
- Two fingers dragging sideways = scroll all long names; icons and check boxes stay. One finger swipes the pages.
- Tap a folder in a tree = select it and jump to its file page.
- Back = one folder up in the visible pane. At the top level, a second back press exits.

Kotlin, no library dependencies. Native part (arm64, `app/src/main/cpp`): fast folder listing, EXIF for WebP, and 7z unpacking with the 7z decoder of the LZMA SDK 26.04 by Igor Pavlov (public domain, `app/src/main/cpp/lzma`, unchanged), built for arm64 only; password-protected archives are not supported. Built by GitHub Actions (`Actions → run → Artifacts → DualFiles-release`).
Needs "all files access" (granted on first start).

Large folders: directories are read in a background thread, each file gets one attribute call, folder item counts are filled in afterwards (shows "…" until then).
