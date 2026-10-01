# Firebase setup

Run `npm run doctor -- --preflight` first. It should pass before you spend time configuring push.

Create a project in the [Firebase console](https://console.firebase.google.com/) and leave Google Analytics off if you do not need it. FCM does not require upgrading to a paid plan. If you started with a Google Cloud project, add Firebase to it in the console first. Install the [Google Cloud CLI](https://cloud.google.com/sdk/docs/install), then run `gcloud auth login` with an account that can enable APIs, create service accounts/keys, create a project IAM role, and assign project roles. An organization may restrict service-account key creation; its administrator must provide an allowed credential instead.

The setup account below temporarily has `roles/firebase.admin` to register the Android app and download its configuration. The runtime sender gets a custom role containing **only** `cloudmessaging.messages.create`. Neither account receives access to other projects. These commands use dedicated account names; do not reuse an account belonging to another application.

From the Pocket checkout, set your existing Firebase project ID and a private key directory **outside the checkout**:

```bash
export POCKET_FIREBASE_PROJECT=your-firebase-project-id
export POCKET_KEY_DIR="$HOME/.config/pocket-keys"
mkdir -p "$POCKET_KEY_DIR"
chmod 700 "$POCKET_KEY_DIR"
umask 077

gcloud services enable firebase.googleapis.com fcm.googleapis.com \
  --project="$POCKET_FIREBASE_PROJECT"

gcloud iam service-accounts create pocket-setup \
  --project="$POCKET_FIREBASE_PROJECT" --display-name='Temporary Pocket setup'
gcloud projects add-iam-policy-binding "$POCKET_FIREBASE_PROJECT" \
  --member="serviceAccount:pocket-setup@$POCKET_FIREBASE_PROJECT.iam.gserviceaccount.com" \
  --role=roles/firebase.admin --condition=None
gcloud iam service-accounts keys create "$POCKET_KEY_DIR/setup.json" \
  --iam-account="pocket-setup@$POCKET_FIREBASE_PROJECT.iam.gserviceaccount.com" \
  --project="$POCKET_FIREBASE_PROJECT"

gcloud iam roles create pocketPushSender --project="$POCKET_FIREBASE_PROJECT" \
  --title='Pocket push sender' --stage=GA \
  --permissions=cloudmessaging.messages.create
gcloud iam service-accounts create pocket-sender \
  --project="$POCKET_FIREBASE_PROJECT" --display-name='Pocket push sender'
gcloud projects add-iam-policy-binding "$POCKET_FIREBASE_PROJECT" \
  --member="serviceAccount:pocket-sender@$POCKET_FIREBASE_PROJECT.iam.gserviceaccount.com" \
  --role="projects/$POCKET_FIREBASE_PROJECT/roles/pocketPushSender" --condition=None
gcloud iam service-accounts keys create "$POCKET_KEY_DIR/sender.json" \
  --iam-account="pocket-sender@$POCKET_FIREBASE_PROJECT.iam.gserviceaccount.com" \
  --project="$POCKET_FIREBASE_PROJECT"

node scripts/configure-firebase.mjs "$POCKET_FIREBASE_PROJECT" \
  "$POCKET_KEY_DIR/setup.json" "$POCKET_KEY_DIR/sender.json"
```

IAM changes can take several minutes to propagate. If a role grant says a newly created service account does not exist, wait briefly and retry that grant before continuing. If setup returns HTTP 403 immediately after granting permissions, wait a few minutes and rerun the final `node` command. Do not broaden the sender's role. If an account or custom role already exists from a previous attempt, inspect it and continue with the remaining steps instead of recreating it.

The script writes client configuration and a copy of **only the sending key** into the private `POCKET_DATA` directory (default `data/`). With a separate sender argument, the setup key is never retained by Pocket. Once the script succeeds, remove the temporary setup identity:

```bash
gcloud projects remove-iam-policy-binding "$POCKET_FIREBASE_PROJECT" \
  --member="serviceAccount:pocket-setup@$POCKET_FIREBASE_PROJECT.iam.gserviceaccount.com" \
  --role=roles/firebase.admin --condition=None
gcloud iam service-accounts delete \
  "pocket-setup@$POCKET_FIREBASE_PROJECT.iam.gserviceaccount.com" \
  --project="$POCKET_FIREBASE_PROJECT"
rm "$POCKET_KEY_DIR/setup.json"
```

Continue with the [backend and Android setup](../README.md#3-start-and-expose-pocket-privately). After pairing, wait for **Firebase push is ready** and use Settings to send a test notification. Confirm it appears on the phone while Pocket is in the background. A successful server response alone does not prove delivery.

For existing credentials, the original two-argument setup command remains supported, but copies that credential for ongoing sending. Prefer the separate sender argument. Changing Firebase projects requires re-pairing/reconfiguring each phone and obtaining its new FCM registration; tokens from the old project cannot receive from the new sender.

Relevant reference: [Firebase IAM permissions](https://firebase.google.com/docs/projects/iam/permissions), [Android app configuration API](https://firebase.google.com/docs/reference/firebase-management/rest/v1beta1/projects.androidApps/getConfig), and [FCM server authorization](https://firebase.google.com/docs/cloud-messaging/auth-server).
