# Login page branding

> Make the sign-in experience match your organization's identity.

The OmniAuth login page is fully themeable from **Manage Jenkins → OmniAuth → Settings**.
Branding is presentation-only — it does not change authentication behavior.

The custom login page is served at `/<prefix>/securityRealm/login`.

## What you can customize

| Setting | Effect |
|---|---|
| **Login heading** | The main heading shown on the login panel |
| **Login tab title** | The browser tab/title text for the login page |
| **Announcement text** | A banner message (e.g. maintenance notice, policy reminder) |
| **Footer text** | Contact or policy text in the page footer |
| **Company logo** | Uploaded logo image shown on the page |
| **Logo position** | Where the logo sits within the layout |
| **Logo size** | The rendered logo height |
| **Background** | The animated/visual background style |
| **Butler style** | The illustrated "butler" artwork on the panel (see below) |

The SSO button label/branding follows the configured
[SSO provider preset](authentication.md#sso-provider-presets).

> _🖼 screenshot: branded login page_

## Butler styles

The login panel can display a themed "butler" illustration. Options range from the classic
Jenkins butler to a set of stylized variants. Beyond a fixed choice, two rotation modes are
available:

| Mode | Behavior |
|---|---|
| **Fixed** | Always show the selected butler |
| **Daily** | "Butler of the day" — deterministic per calendar day (whole team sees the same one) |
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
