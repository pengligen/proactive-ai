# Android Permission Matrix (Extreme Mode)

## Runtime permissions (request via system dialogs)
- `android.permission.ACCESS_FINE_LOCATION`
- `android.permission.ACCESS_COARSE_LOCATION`
- `android.permission.ACCESS_BACKGROUND_LOCATION`
- `android.permission.ACTIVITY_RECOGNITION`
- `android.permission.RECORD_AUDIO`
- `android.permission.CAMERA`
- `android.permission.READ_CONTACTS`
- `android.permission.READ_CALENDAR`
- `android.permission.WRITE_CALENDAR`
- `android.permission.READ_MEDIA_IMAGES`
- `android.permission.READ_MEDIA_VIDEO`
- `android.permission.READ_MEDIA_AUDIO`
- `android.permission.POST_NOTIFICATIONS`
- `android.permission.BLUETOOTH_SCAN`
- `android.permission.BLUETOOTH_CONNECT`
- `android.permission.NEARBY_WIFI_DEVICES`
- `android.permission.BODY_SENSORS`

## Settings/role gated permissions (guided deep-links)
- Usage stats access (`android.permission.PACKAGE_USAGE_STATS`) via Settings.
- Notification listener access (Settings -> Notification Access).
- Health Connect granular record grants.
- Battery optimization ignore for stable background behavior.
- Device admin/role-based capabilities if explicitly needed.

## Restricted/high-risk permissions (single-user experiment only)
- `android.permission.READ_SMS`
- `android.permission.SEND_SMS`
- `android.permission.RECEIVE_SMS`
- `android.permission.READ_CALL_LOG`

## Core policy
- Every permission category has:
  - independent toggle
  - explicit purpose text
  - TTL retention setting
  - one-tap revoke entry
