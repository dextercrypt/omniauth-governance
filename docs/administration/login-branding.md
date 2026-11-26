# Login page branding

> Make the sign-in experience match your organization's identity.

The Rotogate login page is fully themeable from **Manage Jenkins → Rotogate → Settings**.
Branding is presentation-only - it does not change authentication behavior.

The custom login page is served at `/<prefix>/securityRealm/login`.

## What you can customize

| Setting | Effect |
|---|---|
| **Login heading** | The main heading shown on the login panel |
| **Login tab title** | The browser tab/title text for the login page |
| **Announcement text** | A manual banner message (e.g. maintenance notice, policy reminder), always shown while set - see [Scheduled announcements](#scheduled-announcements) for time-based banners |
| **Footer text** | Contact or policy text in the page footer |
| **Company logo** | Uploaded logo image shown on the page |
| **Logo position** | Where the logo sits within the layout |
| **Logo size** | The rendered logo height |
| **Background** | The animated/visual background style |
| **Butler style** | The illustrated "butler" artwork on the panel (see below) |

The SSO button label/branding follows the configured
[SSO provider preset](authentication.md#sso-provider-presets).

## Scheduled announcements

Beyond the single manual **Announcement text**, you can define **recurring scheduled banners**
that appear on the login page automatically during a weekly day/time window - for example
"No production deployments" every Friday, or a "Maintenance tonight" notice for an evening window.

Enable the master **Scheduled announcements** toggle on the settings page, then add one or more
rules. Each rule has:

| Field | Effect |
|---|---|
| **Message** | The banner text shown while the rule is active |
| **Days** | Days of the week the rule applies to; leave empty for *every day* |
| **All day / time window** | Either all day, or a start-end time window. A window whose start is later than its end **wraps past midnight** (e.g. 22:00-02:00) |
| **Priority** | Higher-priority rules are shown first when more than one is active |
| **Enabled** | Turn an individual rule on or off without deleting it |

How banners render on the login page:

- The **manual** announcement (if set) always takes the top slot, followed by any scheduled rules
  that are currently active, highest priority first.
- Rules are evaluated against the **server's local time**.
- At most **two** banners are shown at once, so the login page never becomes a wall of notices.

Scheduled announcements are stored in the plugin's global configuration and round-trip through
[JCasC](../operations/configuration-as-code.md).

## Butler styles

The login panel can display a themed "butler" illustration. Options range from the classic
Jenkins butler to a set of stylized variants. Beyond a fixed choice, two rotation modes are
available:

| Mode | Behavior |
|---|---|
| **Fixed** | Always show the selected butler |
| **Daily** | "Butler of the day" - deterministic per calendar day (whole team sees the same one) |
| **Random** | A different butler on each page render |

A **festive** butler is shown automatically during the late-December window when a rotation
mode is active.

## Logo upload

Upload and manage the company logo from the settings page (the plugin exposes upload and
remove actions for the login logo). Logos are served through a plugin endpoint so they
display on the pre-authentication login page.

## Error page

The login error page inherits the same theming as the login page, so a failed or expired
login stays on-brand.

## Notes

- Branding settings are stored in the plugin's global configuration and can be codified with
  [JCasC](../operations/configuration-as-code.md).
- Keep announcement/footer text concise; the login panel is a small surface.

---
_Related: [Authentication](authentication.md) · [Configuration reference](../reference/configuration-reference.md)_
