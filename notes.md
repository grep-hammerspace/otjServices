## Improvements


1) ~~Find a way to automate login through QMUL Azure Id for everyone, i assume it is already set up to work with everyones QMUL email and password~~
2) ~~Store passwords encrypted, this means you have to think a lot harder about security~~ — resolved, see `password-encryption-plan.html` (AES-256-GCM via `PasswordCipher`, key from `PASSWORD_ENCRYPTION_KEY` env var)
3) Upgrade to AWS, with free tier, not sure how that looks with running privileged containers though, will have to do some more digging
4) Change activity log diffing code to care only about additions, this will solve the issue of previous states being cleared
5) Add a sweeper proc, this will run every once in a while (or on request) and match your activity logs to KSBs and return you some data on how your activities related to the KSBs.
6) 
