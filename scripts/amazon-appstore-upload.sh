#!/usr/bin/env bash
# Puts a Fire TV APK into the app's open Amazon Appstore edit (creating the edit if none is open), replacing the
# edit's APK or uploading a new one. It never commits the edit: submitting for review stays with the owner in the
# Amazon Developer Console. Run only by release.yml (never by hand).
#
# Env: AMAZON_APPSTORE_CLIENT_ID, AMAZON_APPSTORE_CLIENT_SECRET (App Submission API security profile),
#      AMAZON_APPSTORE_APP_ID (e.g. amzn1.devportal.mobileapp.…). Usage: amazon-appstore-upload.sh <apk>
set -euo pipefail
apk="${1:?usage: amazon-appstore-upload.sh <apk>}"
[ -f "$apk" ] || { echo "::error::APK not found: $apk"; exit 1; }
api="https://developer.amazon.com/api/appstore/v1/applications/${AMAZON_APPSTORE_APP_ID:?}"

token=$(curl -fsS -X POST https://api.amazon.com/auth/o2/token \
  --data-urlencode grant_type=client_credentials \
  --data-urlencode "client_id=${AMAZON_APPSTORE_CLIENT_ID:?}" \
  --data-urlencode "client_secret=${AMAZON_APPSTORE_CLIENT_SECRET:?}" \
  --data-urlencode scope=appstore::apps:readwrite | jq -r '.access_token // empty')
[ -n "$token" ] || { echo "::error::Amazon token request returned no access_token"; exit 1; }
echo "::add-mask::$token"
auth=(-H "Authorization: Bearer $token")

edit=$(curl -fsS "${auth[@]}" "$api/edits" | jq -r '.id // empty')
if [ -z "$edit" ]; then
  edit=$(curl -fsS -X POST "${auth[@]}" "$api/edits" | jq -r '.id // empty')
  echo "Opened a new Appstore edit"
fi
[ -n "$edit" ] || { echo "::error::Could not open an Appstore edit"; exit 1; }

apk_id=$(curl -fsS "${auth[@]}" "$api/edits/$edit/apks" | jq -r '.[0].id // empty')
type=(-H "Content-Type: application/vnd.android.package-archive")
if [ -n "$apk_id" ]; then
  etag=$(curl -fsS -D - -o /dev/null "${auth[@]}" "$api/edits/$edit/apks/$apk_id" | awk 'tolower($1)=="etag:" {print $2}' | tr -d '\r')
  curl -fsS -X PUT "${auth[@]}" "${type[@]}" -H "If-Match: $etag" --data-binary @"$apk" "$api/edits/$edit/apks/$apk_id/replace" > /dev/null
  echo "Replaced the edit's APK"
else
  curl -fsS -X POST "${auth[@]}" "${type[@]}" -H "fileName: $(basename "$apk")" --data-binary @"$apk" "$api/edits/$edit/apks/upload" > /dev/null
  echo "Uploaded the APK to the edit"
fi
echo "::notice title=Amazon Appstore::Fire TV APK is in the open edit. Submit it in the Amazon Developer Console when ready."
