# TLS and Certificate Configuration

Use the protocol's actual TLS configuration, not a permissive trust manager or a proxy that strips authentication. Network bootstrap requires mutual TLS. Network consensus and native resource services require mutual TLS outside their explicitly supported loopback development profiles.

For bootstrap, provision the server's certificate/private key and a dedicated trust store of permitted client issuers or certificates. Provision each client's identity and trust for the registry server issuer. All client identities trusted by a bootstrap server can read the contexts in that server's configured allowlist, so choose that trust inventory deliberately.

The resolver verifies the original URI hostname or IP using HTTPS endpoint identification. A valid chain with the wrong SAN is insufficient. `bootstrap+tls://registry-a.internal:4117` needs an identity valid for `registry-a.internal`; using a wildcard bind address or an unrelated container hostname in the URI does not fix a certificate mismatch.

## Build a standard JDK TLS context

The following host-configuration fragment uses ordinary PKCS12 identity and trust stores. Paths and password arrays are supplied through protected host configuration:

```java
KeyStore identity = KeyStore.getInstance("PKCS12");
try (InputStream input = Files.newInputStream(identityPath)) {
    identity.load(input, identityPassword);
}
KeyStore trust = KeyStore.getInstance("PKCS12");
try (InputStream input = Files.newInputStream(trustPath)) {
    trust.load(input, trustPassword);
}
KeyManagerFactory keys = KeyManagerFactory.getInstance(
        KeyManagerFactory.getDefaultAlgorithm());
keys.init(identity, privateKeyPassword);
TrustManagerFactory issuers = TrustManagerFactory.getInstance(
        TrustManagerFactory.getDefaultAlgorithm());
issuers.init(trust);
SSLContext tls = SSLContext.getInstance("TLS");
tls.init(keys.getKeyManagers(), issuers.getTrustManagers(), null);
```

Pass the resulting server/client context to the concrete endpoint/resolver. Do not put store passwords, PINs or native authentication keys in an endpoint URI, registry metadata, a bundle manifest or a code example committed with a deployment.

## Rotation and verification

Before changing trust, establish which endpoints and identities depend on the old material. Provision the replacement certificate with the same intended SANs and authorized identity mapping. Follow the concrete provider's credential refresh/lifecycle API; a newly created `SSLContext` does not automatically reconfigure every already-running service.

Verify a successful authenticated connection and negative cases: no client identity, untrusted issuer, wrong hostname and wrong registry context. A connection timeout alone is not evidence that a certificate policy worked.
