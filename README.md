# CCS Calendar Sync

Android app that turns screenshots of MobileCCS trip pages into Google Calendar events.

- In MobileCCS, open a trip's details page and take a screenshot.
- The app notices the new screenshot, reads it **on the phone** (ML Kit text recognition,
  bundled model, no internet permission), and adds one event per duty day:
  report time to release (15 min after the last arrival), with the flights and layover
  in the description. Times are local to each airport.
- Screenshot the trip again after a change and its events are replaced, not duplicated.
- You can also share any screenshot to the app, or tap "Check screenshots" in the app.

Build: GitHub Actions (`.github/workflows/build.yml`) publishes the APK on the Releases page.

Airport time zones: `app/src/main/assets/airport_tz.csv`, generated from
[mwgg/Airports](https://github.com/mwgg/Airports) (MIT License, Copyright (c) 2014 mwgg).
