# Dash

A personal dashboard for managing my phone plan, built around the cheapest way I've found to get full phone coverage in Canada: a **Koodo Mobile** prepaid account for calls and texts, plus an **SMSPool.net** eSIM for data.

> [!CAUTION]
> :rotating_light: **Free and Open-Source Android is under threat.** Google is moving to turn Android into a locked-down platform, restricting your freedom to install the apps of your choice. Make your voice heard — [**Keep Android Open**](https://keepandroidopen.org/).

## Why this combination

- Koodo Mobile prepaid: <https://www.koodomobile.com/en/prepaid-plans>
- SMSPool.net eSIM: <https://www.smspool.net/esim/manage>

I buy a 100 MB eSIM every week for $0.40 USD. Over a year that's **$20.80 USD (~$29.56 CAD)**.

I also buy the $100 Koodo plan, which comes to **$122.56 CAD** ($100 + $6.60 CAD + QC taxes).

**Total: $152.12 CAD per year (~$12.68 CAD per month)**, for:

- 400 minutes of calling (incoming unlimited)
- 400 text messages (incoming unlimited)
- Calling features: voicemail, call display, call waiting, call forwarding, conference calling (voicemail and call forwarding use calling minutes)
- 100 MB of data per week (~5.2 GB per year)

## What the app supports

- **Koodo Mobile** — sign in (with SMS 2FA), then see your renewal date, minutes, and texts. Credentials are stored encrypted and reconnect silently on open.
- **SMSPool eSIM** — list your eSIMs with data remaining and expiry, install them (one-tap, QR, or manual), and browse/buy plans with poor-value deals filtered out.
- **SIMs on this phone** — lists the SIMs/eSIMs installed on the device and which is active for data, calls, and texts.

Credentials and the API key are encrypted with the Android Keystore and excluded from backup; the app only talks to Koodo and SMSPool.

---

Not endorsed by or affiliated with Koodo Mobile or SMSPool.net.
