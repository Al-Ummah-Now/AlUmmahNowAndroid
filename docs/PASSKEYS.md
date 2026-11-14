# Passkeys in Studio and Admin

Spec: AlUmmahNowWiki `epics/AUTH-PASSKEYS-passkeys-everywhere.md`, section "Native apps" (it wins over this page).

Both apps show two choices on the sign-in screen:

1. **Sign in with a passkey**: the app calls Credential Manager (`androidx.credentials`) itself. No browser opens.
   The OS sheet offers Google Password Manager, 1Password and other providers, a security key, or a phone by QR.
2. **Sign in another way**: the browser sign-in (Custom Tabs + PKCE) we already had, unchanged.

Each app's account screen also has **Add a passkey** (Studio: the account sheet; Admin: Settings).

## How it works

| Step | Call |
|---|---|
| Start | aun-auth begins an ordinary PKCE sign-in (`beginLoginWithPrompt`). The app never opens that URL: it takes its `code_challenge` and `state` |
| Options | `POST {issuer}/native/passkey/options` `{client_id, code_challenge, code_challenge_method:"S256"}` → `{challenge_id, publicKey}` |
| OS sheet | `CredentialManager.getCredential(GetPublicKeyCredentialOption(publicKey))` → `authenticationResponseJson` |
| Verify | `POST {issuer}/native/passkey/verify` `{challenge_id, client_id, credential}` → `{code}` |
| Tokens | aun-auth's `completeLogin("{redirect_uri}?code=…&state=…")`: it checks `state` and exchanges the code with the PKCE verifier at the normal token endpoint, then stores the account. Same code path as the browser sign-in |
| Add | `POST /native/passkey/register/options` (Bearer) → `CreatePublicKeyCredentialRequest` → `POST /native/passkey/register/verify` (Bearer). On `401 step_up_required`, the app asks the person to sign in again (browser, `prompt=login&max_age=900`), then retries the add by itself |

Code: `core-bridge/.../auth/passkey/` (API client, flow, errors), `feature-passkey/` (Credential Manager adapter,
view model, buttons, dialogs, en/ar copy), `CoreAccountSession.signInWithPasskey/addPasskey`.

Cancel in the OS sheet is silent. Other problems are in-app Compose dialogs; "no passkey found" and failed sign-ins
offer **Sign in another way**. The button is hidden below Android 9, where Credential Manager can't do passkeys. Admin
dialogs inherit `FLAG_SECURE`.

## Digital Asset Links (server config)

Credential Manager only lets an app use passkeys for an RP id whose site lists the app. Android fetches
`https://{rp_id}/.well-known/assetlinks.json` from the **RP id host** (the issuer host without `auth.`), not from
`auth.*`:

| Env | RP id | assetlinks.json URL |
|---|---|---|
| QA | `qa.alummahnow.net` | `https://qa.alummahnow.net/.well-known/assetlinks.json` |
| Prod | `alummahnow.net` | `https://alummahnow.net/.well-known/assetlinks.json` |

Each needs one statement per package with `delegate_permission/common.get_login_creds`:

```json
[{
  "relation": ["delegate_permission/common.get_login_creds"],
  "target": {
    "namespace": "android_app",
    "package_name": "net.alummahnow.studio",
    "sha256_cert_fingerprints": ["<Play App Signing SHA-256>", "<upload key SHA-256>"]
  }
}]
```

| Env | Packages |
|---|---|
| QA | `net.alummahnow.studio.qa`, `net.alummahnow.admin.qa` |
| Prod | `net.alummahnow.studio`, `net.alummahnow.admin` |

The server must also accept the app's WebAuthn origin, `android:apk-key-hash:<base64url(SHA-256 of the signing
cert)>`, one per fingerprint below.

### Signing keys and fingerprints

| Key | Signs | SHA-256 | `android:apk-key-hash:` origin |
|---|---|---|---|
| CI pre-release key (`aun-prerelease`) | Studio QA/prod release APKs on GitHub pre-releases (until the upload key exists) | `5A:1B:3D:2B:57:6E:9A:2D:F0:BF:1B:E3:F1:8C:40:20:13:F9:5C:09:EA:EE:7C:F7:31:10:C4:2F:B5:69:DD:C0` | `android:apk-key-hash:Whs9K1dumi3wvxvj8YxAIBP5XAnq7nz3MRDEL7Vp3cA` |
| Play App Signing key, per app | what Play installs | owner: Play Console → app → Test and release → App integrity → App signing | base64url of it |
| Upload key | bundles CI uploads; internal-sharing installs | owner: `keytool -list -v -keystore upload.jks -alias aun-upload` | base64url of it |
| A developer's debug key | local `installQaDebug` builds | `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android` | base64url of it |

CI's own debug builds are signed with a fresh debug key on every run, so passkeys never work in CI debug artifacts;
test passkeys on QA with the pre-release QA APK (Studio) or a local debug build whose fingerprint is in the QA
assetlinks file. Never list a debug fingerprint in the **prod** file.

To turn a SHA-256 into the origin: `python3 -c "import base64,sys;print('android:apk-key-hash:'+base64.urlsafe_b64encode(bytes.fromhex(sys.argv[1].replace(':',''))).decode().rstrip('='))" <SHA-256>`.

The apps also carry the reverse link (`asset_statements` in the manifest, per flavour in `src/qa` and `src/prod`)
pointing at the same file.
