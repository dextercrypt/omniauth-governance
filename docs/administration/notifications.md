# Notifications

> Route access and security events to email, Slack, and Microsoft Teams.

Omni Auth can notify administrators and stakeholders about security-relevant events —
new JIT requests, access changes, stale-account warnings, access-review digests, and more —
across three channels, each independently configurable with its own event subscriptions.

Managed at **Manage Jenkins → Omni Auth → Notifications**.

## Channels

| Channel | Transport | Key settings |
|---|---|---|
| **Email** | SMTP (via Jakarta Mail) | host, port, TLS, username/password, from address/name, reply-to, recipients |
| **Slack** | Incoming webhook | webhook URL |
| **Microsoft Teams** | Incoming webhook | webhook URL |

Each channel has a master enable toggle in addition to the global **notifications enabled**
switch. See the [Configuration reference](../reference/configuration-reference.md) for the
full field list.

> _🖼 screenshot: notifications settings_

## Per-event routing

Each channel subscribes to its own list of **event types** (e.g. email might receive
everything, while Slack receives only high-signal events). Configure the event lists per
channel so noise goes where it belongs.

## Testing

Each channel provides a **test** action so you can confirm connectivity before relying on
it:

- **Send test email**
- **Send test Slack message**
- **Send test Teams message**

Use these after any change to SMTP settings or webhook URLs.

## Reliability

- **Retry on failure.** Delivery is retried with backoff so a transient outage doesn't drop
  a notification.
- **Notification log.** Sent notifications are recorded; the log can be viewed and cleared
  from the console for troubleshooting.

## Email templates & preview

Notification emails use branded templates. You can:

- **Preview** an email template for a given event type with sample data before enabling it.
- Customize header logo and footer note via the notification branding settings.

> _🖼 screenshot: email preview modal_

## Recommended setup

1. Configure **email** first (most reliable for audit-relevant events); send a test.
2. Add **Slack** or **Teams** for real-time, high-signal events (JIT requests, break-glass
   activation).
3. Subscribe the [access review](access-review.md) digest to email.

---
_Next: [User lifecycle](user-lifecycle.md)_
