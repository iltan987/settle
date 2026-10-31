# ADR-003: Drive layout, per-file metadata and restore source

- Status: accepted
- Date: 2026-10-30

## Context
Albums, folders, favourites and pins must survive a phone reset. With the `drive.file` scope the
app sees only files it created. `appDataFolder` is hidden but is deleted when the user disconnects
the app in Drive settings, which is exactly a situation where restore matters. Android backup and
Smart Switch would restore a local index whose MediaStore references no longer match the phone.

## Decision
- A visible `Settle/` folder in My Drive (`Settle (debug)/` for debug builds):
  - `media/YYYY/MM/<original file name>` holds originals, byte-for-byte.
  - `_previews/YYYY/MM/<itemId>.jpg` holds previews of evicted items only.
  - `_library/library.json` (folder rules, pins, settings), `_library/albums/<albumId>.json`
    (one manifest per album), `_library/trash.json`.
- Every file carries `appProperties` (`st.v`, `st.id`, `st.sha`, `st.kind`, `st.path`, `st.taken`,
  `st.dim`, `st.sf`, `st.fav`, `st.alb`, `st.dev`) and a JSON copy in `description`.
  Amended 2026-10-31 (ADR-013): `st.sha` is dropped, since it isn't known when the upload
  starts and Drive's own `sha256Checksum` serves the same purpose. A value that would exceed
  Drive's 124-byte property limit (a long `st.path`) is kept only in the description. If `_library`
  is lost, the library can still be rebuilt from `files.list` alone.
- Settle's local data is excluded from Android cloud backup and device transfer
  (`data_extraction_rules.xml`). Drive is the only restore source.

## Consequences
Files are readable in Drive web as a bonus. Album manifests are rewritten whole on change
(debounced); fine for one device, revisit for multi-device sync.
