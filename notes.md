## Improvements


1) ~~Find a way to automate login through QMUL Azure Id for everyone, i assume it is already set up to work with everyones QMUL email and password~~
2) ~~Store passwords encrypted, this means you have to think a lot harder about security~~ no longer needed, we are switching to a mobile app model, send creds over https tunnel and we can just store creds on the users devices. React-Native Key Store.
3)~~Upgrade to AWS, with free tier, not sure how that looks with running privileged containers though, will have to do some more digging~~
4) ~~Change activity log diffing code to care only about additions, this will solve the issue of previous states being cleared~~ we are switching to mobile app model, dont need this anymore
5) ~~Add a sweeper proc, this will run every once in a while (or on request) and match your activity logs to KSBs and return you some data on how your activities related to the KSBs.~~ out of scope
6) ~~Decide whether or not we should keep Mongo or switch to sthg AWS native, maybe DocumentDB. It may be cheaper than ingress~~ no need, Atlas free tier has plenty of space
7) Smarter session tracking: ie if someone tries a login with azure id and soon after tries another, they will alr be logged in, which case we dont need to return a challenge code, and we dont need to return the thing about an exisiting push (which is sorta inaccurate), we can maybe just do a poll before we even start the login flow to see if the user is logged in alr or not, if yes we can just POST with existing cookies
8) performance enhancements ? pretty open ended - biggest bottleneck is probably nio when talking to the login services, not sure thugh, needs examining
9) 
