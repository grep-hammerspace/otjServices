## Improvements

1) Smarter session tracking: ie if someone tries a login with azure id and soon after tries another, they will alr be logged in, which case we dont need to return a challenge code, and we dont need to return the thing about an exisiting push (which is sorta inaccurate), we can maybe just do a poll before we even start the login flow to see if the user is logged in alr or not, if yes we can just POST with existing cookies
2) performance enhancements ? pretty open ended - biggest bottleneck is probably nio when talking to the login services, not sure thugh, needs examining
3) set up protected branch on remote "tailscale" which is mostly the same thing as master - but without the aws infra stuff, so that it can be run via bootstrap.sh and self-hosting as a docker compose deployment.
4) User an opensource/free model instead of relying on Anthropic - OpenRouter Nvidia Nim?
5) Update the LLM system prompt, so that you can be less specific about a time. Ideally we want to just say what we did and it gets logged
6) Some kinda of automated calendar scraper, that will fetch details of lectures and labs from a uni calendar and prepare an activty log for everyone, that way people dont have to log uni events themselves. They can just log the extra stuff
