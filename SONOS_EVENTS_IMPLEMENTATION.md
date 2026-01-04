# Sonos Event Subscriptions Implementation

## Overview
Replaced the continuous 3-second polling mechanism with UPnP event subscriptions for track metadata changes. The polling now acts as a fallback for devices that don't successfully subscribe to events.

## Architecture

### Key Components

1. **SonosEventSubscription.kt** - New class managing UPnP event subscriptions:
   - Embeds a lightweight NanoHTTPD HTTP server to receive NOTIFY requests from Sonos devices
   - Subscribes to AVTransport service events on each discovered device
   - Parses incoming event notifications for track metadata changes
   - Automatically renews subscriptions before they expire (at 85% of timeout)
   - Gracefully handles subscription failures with fallback to polling

2. **MainActivity.kt (SonosViewModel)** - Updated to use event subscriptions:
   - Initializes event server at startup via `initializeEventSubscriptions()`
   - Tracks subscribed devices in `subscribedDevices` set
   - Polling loop only polls devices NOT in the subscribed set
   - Event callbacks immediately update track information when changes occur
   - Cleanup in `onCleared()` to stop event server gracefully

### Dependencies
- **NanoHTTPD** (org.nanohttpd:nanohttpd:2.3.1) - Lightweight HTTP server for Android
- Existing Ktor client libraries - For making HTTP SUBSCRIBE/UNSUBSCRIBE requests

## How UPnP Event Subscriptions Work

### Subscription Flow
```
1. Device discovered by SonosDiscovery
   ↓
2. ViewModel notified via Flow<List<SonosDevice>>
   ↓
3. ViewModel calls eventSubscription.subscribeToDevice(device)
   ↓
4. SonosEventSubscription sends SUBSCRIBE request:
   POST /MediaRenderer/AVTransport/Event HTTP/1.1
   CALLBACK: <http://local-ip:8888/notify>
   NT: upnp:event
   TIMEOUT: Second-3600
   ↓
5. Sonos device responds with Subscription ID (SID)
   ↓
6. Device added to subscribedDevices set
   ↓
7. Auto-renewal timer started (triggers at 85% of timeout)
   ↓
8. When track changes: device sends NOTIFY to callback URL
   ↓
9. NanoHTTPD server receives NOTIFY
   ↓
10. Parses LastChange XML for metadata changes
    ↓
11. Queries device for current track info
    ↓
12. ViewModel updates with new track metadata (~100ms latency)
```

### Renewal Flow
Before subscription timeout expires (at 3060 seconds for 3600s timeout):
```
1. Auto-renewal timer fires
   ↓
2. Send renewal request with existing SID
   ↓
3. On success: update subscription timestamp
   ↓
4. On failure: remove from subscriptions, attempt full resubscribe
```

## Benefits

1. **Significantly Reduced Network Traffic**
   - Old: Every 3 seconds, query all devices for track info
   - New: Only receive events when track actually changes
   - Estimated 95%+ reduction in network calls

2. **Much Lower Latency**
   - Old: Up to 3 seconds between track change and UI update
   - New: ~100ms between Sonos event and UI update

3. **Improved Battery Life**
   - Fewer network requests = less WiFi radio activity
   - Especially beneficial on battery-powered devices

4. **Resilient Architecture**
   - Gracefully falls back to polling if subscriptions fail
   - Mixed mode: Some devices subscribed, others polled
   - Device-specific recovery without affecting others

5. **Scalable**
   - Can efficiently handle many devices
   - Event-driven scales better than polling

## Logging & Debugging

### Key Log Tags
- `SonosEventSubscription` - Event server, subscriptions, NOTIFY handling
- `SonosViewModel` - Event callbacks, polling activity

### Expected Log Output (Successful Startup)
```
SonosEventSubscription: Starting event server on 192.168.1.100:8888
SonosEventSubscription: Event server started successfully
SonosViewModel: Event subscriptions initialized successfully
SonosViewModel: Attempting subscription to Living Room
SonosEventSubscription: Successfully subscribed to Living Room: SID=uuid:12345...
SonosViewModel: Subscribed to Living Room
```

### Expected Log Output (Event Received)
```
SonosEventSubscription: Received NOTIFY request from 192.168.1.50
SonosEventSubscription: Track or state change detected for device 192.168.1.50
SonosEventSubscription: Updated track info for 192.168.1.50: Now Playing Track Title
SonosViewModel: Event callback: Track changed on Living Room
```

### Expected Log Output (Fallback to Polling)
```
SonosEventSubscription: Failed to start event server
SonosViewModel: Event subscriptions initialized, but will use polling
SonosViewModel: Polling 2 unsubscribed devices (0 via events)
```

## Potential Issues & Mitigations

### Issue: Event Server Port Already in Use
**Symptom**: "Failed to start event server"
**Cause**: Another app is using port 8888
**Mitigation**: Could modify to use dynamic port, but 8888 is hardcoded for simplicity
**Workaround**: Stop other apps using port 8888 or restart phone

### Issue: Sonos Device Cannot Reach Callback IP
**Symptom**: Subscriptions fail, falls back to polling
**Cause**: Device on different network, firewall blocking, or wrong network
**Mitigation**: Verify device and phone are on same WiFi network
**Solution**: Falls back to polling automatically

### Issue: Network Changes (WiFi disconnect/reconnect)
**Symptom**: Subscriptions stop working after WiFi reconnect
**Cause**: IP address may change, event server becomes unreachable
**Mitigation**: Auto-renewal failures trigger resubscription with new IP
**Solution**: Manual refresh button restarts all subscriptions with new IP

### Issue: Subscription Timeouts
**Symptom**: After ~1 hour, subscriptions stop working
**Cause**: Subscription timeout reached without renewal
**Mitigation**: Auto-renewal at 85% of timeout ensures renewal well before expiry
**Solution**: Renewal happens automatically every ~50 minutes

## Testing Scenarios

### 1. Basic Event Subscription
1. Start app on WiFi network with Sonos device
2. Verify logs show "Event subscriptions initialized successfully"
3. Change track on Sonos speaker
4. Verify logs show "Event callback: Track changed..."
5. Verify UI updates within ~100ms

### 2. Fallback to Polling
1. Block port 8888 on firewall (simulating server failure)
2. Start app
3. Verify logs show "Failed to start event server"
4. Verify logs show "Polling N unsubscribed devices"
5. Verify track metadata still updates (every 3 seconds)

### 3. Mixed Mode (Some subscribed, some polled)
1. Have 3+ Sonos devices
2. Manually unsubscribe one device in code
3. Start app
4. Verify logs show mixed mode:
   - "Subscribed to Device A"
   - "Failed to subscribe to Device B"
   - "Polling 1 unsubscribed devices (2 via events)"

### 4. Subscription Renewal
1. Start app, verify subscriptions active
2. Wait ~50 minutes
3. Verify logs show renewal messages
4. Track changes should still work

### 5. Network Reconnect
1. Start app with WiFi
2. Turn off WiFi
3. Wait for subscription failures (logs)
4. Turn WiFi back on
5. Manually trigger refresh button
6. Verify subscriptions re-established with new IP

## Configuration Reference

Located in `SonosEventSubscription.kt`:
```kotlin
companion object {
    private const val TAG = "SonosEventSubscription"
    private const val SUBSCRIBE_TIMEOUT = 3600 // seconds (1 hour)
    private const val EVENT_SERVER_PORT = 8888
}
```

Located in `SonosViewModel`:
```kotlin
private val POLLING_INTERVAL = 3000L // 3 seconds fallback
```

## Performance Metrics (Expected)

- **Event server startup**: ~100ms
- **Subscription request**: ~500-1000ms per device
- **Track change latency**: ~50-150ms from device to UI
- **Memory overhead**: ~2-5MB for event server + subscriptions
- **Network traffic**: ~95% reduction compared to polling

## Future Improvements

1. **Dynamic port assignment**: If port 8888 is in use, try next available port
2. **Subscription pooling**: Subscribe to multiple services (RenderingControl, etc.)
3. **Persistent SID storage**: Save SIDs across app restarts
4. **Battery optimization**: Pause subscriptions when screen off
5. **Metrics collection**: Track subscription success rates

## References

- UPnP Device Architecture spec: http://upnp.org/specs/arch/
- Sonos AVTransport service: http://192.168.x.x:1400/xml/AVTransport1.xml
- NanoHTTPD: https://github.com/NanoHttpd/nanohttpd
