## What's New

- Faster app launch & smoother SMS scanning (batched DB writes & off-main-thread UI state)
- Fixed JSON backup/restore errors & Release R8 model parsing
- New dedicated Archived Accounts screen (Accounts, My Accounts & Settings)
- Long-press multi-select on accounts for batch Archive, Restore & Permanent Delete

---

### Performance & Startup
- **Eliminated First-Open Lag:** Deferred the initial startup scan slightly so the first UI frame renders immediately, moved `AccountsViewModel` state combination off the main thread, and removed redundant eager `SettingsViewModel` initializations.
- **Batched SMS Transactions:** Chunked SMS scan database writes into 25-item transactions with coroutine yields between chunks for smooth scrolling and UI responsiveness during background scans.

### Backup & Restore Reliability
- **Fixed JSON Backup/Restore Errors:** Added ProGuard/R8 `-keep` rules for backup, database, update, and web portal models so release builds serialize and deserialize cleanly.
- **Resilient JSON Parsing:** Added UTF-8 BOM stripping, helpful detection/error message if a CSV file is selected in JSON import, null-safe field sanitization, non-finite number (`NaN`/`Infinity`) sanitization, and fault-tolerant persistent MediaStore Downloads fallback.

### Account Management
- **Dedicated Archived Accounts Screen:** Added a standalone screen (`Archived Accounts`) reachable from the Accounts tab, My Accounts top bar, and Settings -> Accounts to review stored SMS, clean up messages, restore accounts, or permanently delete empty accounts.
- **Long-Press Multi-Selection:** Long-press any account card (excluding built-in Cash) in Accounts, My Accounts, or Archived Accounts to multi-select and batch **Archive**, **Restore**, or **Permanently Delete** (including stored SMS messages).

### Recent Additions Included
- **Search & Discovery:** Keyword search in SMS Review and a quick Search Key List in Transaction History.
- **Wi-Fi / Hotspot Web Portal:** View, add, and edit data from a browser on the same local network.
- **Cards & Liabilities:** Redesigned Cards tabs, prioritized active unpaid bills with red danger Pay Bill actions, and aggregated card outstanding totals into Account liabilities.
- **Auto-Updates & CSV Backups:** In-app GitHub release updater and saved CSV backup restoration fixes.
