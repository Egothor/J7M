# Loopback alias TLS test material

`alias-test.crt` and `alias-test.key` are a deliberately public, self-signed test-only identity, never production credentials. The certificate has IP SAN `127.0.0.2` and server/client authentication usages. Generated on 2026-11-04 for local endpoint acceptance tests with OpenSSL; no OpenSSL command runs in the test suite. Expiry is ten years from generation. Tests trust this leaf explicitly and do not override the TLS authority.

Recreate only when intentionally updating the fixture:

```sh
openssl req -x509 -newkey rsa:2048 -nodes -keyout alias-test.key -out alias-test.crt -sha256 -days 3650 -subj '/CN=J7M loopback alias test only' -addext 'subjectAltName=IP:127.0.0.2' -addext 'extendedKeyUsage=serverAuth,clientAuth'
```
