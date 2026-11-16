# atvOS fork policy

This repository publishes the owner’s verified TCL launcher as atvOS. The upstream
instructions below remain the reference for device safety and the inherited tools.
For this fork only, INTERNET and READ_TV_LISTINGS are intentional: ShelfArtwork.java
loads recommendation images and ShelfPrograms.java reads local Android TV listings.
No other source may add network access. The APK budget is 655,360 bytes (640 KiB),
measured after adding the recommendation shelf. CI and its permission and budget
checks are updated to this explicit fork policy. Upstream restrictions on these
features describe contributions to Gathin23/cleanyourtv, not publication of this fork.
Do not publish personal configs, backups, keys or Apple font files. Keep the current
safe installation, restore and protected-package logic intact.


The imported TCL switches `poster_shelf` and `youtube_wallpaper_only` remain on the
screenshot wanted list until sanitized public-build captures are available. Do not
publish the owner’s private screenshots just to satisfy the upstream gallery rule.

The owner keeps only the Russian README. Translated README files, Italian guide
copies and upstream English promotional artwork have been removed. Preserve actual
interface screenshots and launcher locale resources. Promotional card generation
helpers are inherited tools, not required documentation assets for this fork.
