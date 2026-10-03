# Self-hosting the OTJ backend

Run your own copy of the API on a machine you own, reachable only from your tailnet, and point the
OTJ mobile app at it. One person, one account, no sign-up.

## What you need

- **Nix**, for the shell with Podman in it: <https://nixos.org/download>.
- **Tailscale** on this machine and on your phone, both on the same tailnet:
  <https://tailscale.com/download>.
- In the Tailscale admin console, under **DNS**, turn on **MagicDNS** and **HTTPS Certificates**.
  `tailscale serve` needs both to give the API an `https://….ts.net` address.
- An **Anthropic API key**, which turns your notes into activity rows.
- Rootless Podman needs subordinate IDs for your user. NixOS sets them up. On other distributions,
  check that `/etc/subuid` and `/etc/subgid` have a line for you, and install `uidmap`
  (Debian/Ubuntu) if `newuidmap` is missing.

## Run it

```bash
sudo tailscale set --operator=$USER   # once, so serve doesn't need root
cp .env.example .env                  # from the repo root, then fill it in
cd deploy && nix-shell
./bootstrap.sh --prod
```

`--prod` detaches, so the stack keeps running after you close the terminal, and prints the log
file to follow. When it's done, it prints your server's address:

```
https://<machine>.<tailnet>.ts.net
```

On the app's sign-up screen, tap **Hosting the backend yourself?**, enter that address, and you
land on Log Activities. Set your learner ID on the Submit tab before your first submission.

| Command | |
|---|---|
| `./bootstrap.sh` | the same, in the foreground |
| `./bootstrap.sh --stop` | stop the stack, keeping the data |
| `clean-mongo` | stop it and wipe the Mongo data volume |
| `podman-compose -f podman-compose.yaml logs -f app` | the API's log |

## How it fits together

```
phone ──tailnet (WireGuard + TLS)──▶ tailscale serve :443 ──▶ 127.0.0.1:8945  app
                                                                     │
                                                              127.0.0.1:27017 mongo
```

Both containers publish on loopback only, so `tailscale serve` is the only way in from outside the
machine. TLS ends at `tailscale serve` on this machine, which is why the app sends your OneAdvanced
password in the request body without the extra sealing the hosted service uses: nothing between
the phone and your machine can read it. It is used for the login and then discarded, never stored.

## Trust model

**The API has no authentication.** Anyone who can reach it is treated as you. That is safe because
of the loopback binding above plus your tailnet, and only while both hold:

- Don't publish `8945` on anything but `127.0.0.1`.
- Anyone on your tailnet can reach it. If you share the tailnet with other people, restrict this
  machine's port 443 to your own devices with a Tailscale ACL.
- Don't put it behind `tailscale funnel` or any other public proxy.
