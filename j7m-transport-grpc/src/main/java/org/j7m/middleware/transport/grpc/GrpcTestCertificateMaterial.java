/*
 * BSD 4-Clause License
 *
 * Copyright (c) 2026, Leo Galambos and the Egothor team
 * Website of the Egothor team: https://www.egothor.org
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * 1. Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * 3. All advertising materials mentioning features or use of this
 * software must display the following acknowledgement:
 *
 * This product includes software developed by Leo Galambos and the Egothor
 * team (https://www.egothor.org).
 *
 * 4. Neither the name of the copyright holder nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 * IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
 * TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.j7m.middleware.transport.grpc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Deterministic self-signed PEM material for tests and demos.
 *
 * <p>The embedded material is not a production secret. It is fixed localhost
 * test credential material used to exercise gRPC mTLS behavior in repeatable
 * integration tests. The private key content is written only to caller-owned
 * temporary directories and is never surfaced in status, errors, or audit
 * events.</p>
 *
 * @param certificate PEM certificate path
 * @param privateKey PEM private-key path
 */
public record GrpcTestCertificateMaterial(Path certificate, Path privateKey) {
    /**
     * Creates material paths.
     */
    public GrpcTestCertificateMaterial {
        certificate = certificate.toAbsolutePath();
        privateKey = privateKey.toAbsolutePath();
    }

    /**
     * Writes deterministic localhost test material.
     *
     * @param directory target temporary directory
     * @param commonName ignored compatibility parameter
     * @return generated material paths
     */
    public static GrpcTestCertificateMaterial generate(final Path directory, final String commonName) {
        try {
            Files.createDirectories(directory);
            boolean clientMaterial = directory.getFileName().toString().toLowerCase(java.util.Locale.ROOT).contains("client");
            Path certificate = directory.resolve("localhost.crt");
            Path privateKey = directory.resolve("localhost.key");
            Files.writeString(certificate, clientMaterial ? CLIENT_CERTIFICATE : SERVER_CERTIFICATE, StandardCharsets.US_ASCII);
            Files.writeString(privateKey, clientMaterial ? CLIENT_PRIVATE_KEY : SERVER_PRIVATE_KEY, StandardCharsets.US_ASCII);
            return new GrpcTestCertificateMaterial(certificate, privateKey);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to write grpc test certificate", exception);
        }
    }

    private static final String SERVER_CERTIFICATE = """
            -----BEGIN CERTIFICATE-----
            MIIDHzCCAgegAwIBAgIUaoeHjyFE+cqM/dQolyxVkqCJSa0wDQYJKoZIhvcNAQEL
            BQAwFDESMBAGA1UEAwwJbG9jYWxob3N0MB4XDTI2MDcwNjEzNTkzOFoXDTM2MDcw
            MzEzNTkzOFowFDESMBAGA1UEAwwJbG9jYWxob3N0MIIBIjANBgkqhkiG9w0BAQEF
            AAOCAQ8AMIIBCgKCAQEA1YqdeXDwctIHqfIBGNZu+AdVrqkCNXsTTyZ5Zqs0dUiJ
            BgWUnHAEJ+1qVYUT6V/u6SkBiSnYEplrnNW6gpSCk23s5mNiS6HqB8vuPNskbvK+
            1QtIZbt9nu0Q5lV5FuIspioE3Q0AyLLaXUjKS5YIt8JmqPr/hZH85nIMD5J2TTNV
            U+P4Ew1M6XNghCenhRuKkJryJej39QeaakoDpAlxKmYxByGiE8/nax5OkWh/5JZR
            b/0B3z0mZUVNAqTBf8oNPgBxhldRPisYgpmAogKhRRLc+kX5MVZdiOhqo7Xyykvg
            YVQis9ftoX/FZtSgLKKtnnZXSLFa2UzjF2P/e/1X1wIDAQABo2kwZzAdBgNVHQ4E
            FgQUA3JADQXQG+LJlyogPtJcbTktXwYwHwYDVR0jBBgwFoAUA3JADQXQG+LJlyog
            PtJcbTktXwYwDwYDVR0TAQH/BAUwAwEB/zAUBgNVHREEDTALgglsb2NhbGhvc3Qw
            DQYJKoZIhvcNAQELBQADggEBANH0PTxh+cPiO37ZrG3vzmZVDcAvHlF5YG5N1uP4
            W68TatRww38s8a23nVG1oL17nP1zq3lOsET56HknAecsStUCVMIYlvzr8djCni8V
            C190nx37ik7CvFrWXmP0WH25ToUpP1M5n4lpFap19AHm3o4LDi6/nWQNYcyBBHvk
            eXJaE6GPNRL4ck8DknQv/nm0HnQpJoILxkTLoIw3wLn1YWxaAiGQpynZESqaxE9t
            fs6T/K3t6tcPI8+/Zsb3xBI+v+Yr6LnMCsZXLg7oyPnPb7+X58JXNB9y1zVTlNaA
            +rCiQj5+hKft6XyUBJfmqMDX+AVMKkiTvFpOlZf+oKj3LuQ=
            -----END CERTIFICATE-----
            """;

    private static final String SERVER_PRIVATE_KEY = """
            -----BEGIN PRIVATE KEY-----
            MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDVip15cPBy0gep
            8gEY1m74B1WuqQI1exNPJnlmqzR1SIkGBZSccAQn7WpVhRPpX+7pKQGJKdgSmWuc
            1bqClIKTbezmY2JLoeoHy+482yRu8r7VC0hlu32e7RDmVXkW4iymKgTdDQDIstpd
            SMpLlgi3wmao+v+FkfzmcgwPknZNM1VT4/gTDUzpc2CEJ6eFG4qQmvIl6Pf1B5pq
            SgOkCXEqZjEHIaITz+drHk6RaH/kllFv/QHfPSZlRU0CpMF/yg0+AHGGV1E+KxiC
            mYCiAqFFEtz6RfkxVl2I6GqjtfLKS+BhVCKz1+2hf8Vm1KAsoq2edldIsVrZTOMX
            Y/97/VfXAgMBAAECggEABuax7CjQPIW5Ja7ihjD36SAjjr97iMrvdtFhfwXtbhTo
            CL/Ja7zbBYdQtm7Uk+g7ymE+4XXuu7sllS37FxlBlw4oZ9GybYtQCux2SJU0ejly
            HWR99Hu4tZgnwanlfe3VT5FwEszGx7WwBO/DSeoRYg7wTmm+NEzhhXkOFrq/BAOj
            jbeMUKLcSxArhZqhbJz9M1QdsmJElxRGVdTO82wOfr5ic65PGJciqeREtI2FVgCt
            HMCR09ax49D3BfwbZC8yFN+EqRAg+yQOpR/NKttfByzR6Il1tuiyKw0gBEx8bHXW
            dFO4QWVxeDjhGBjbkRcbkOWIGIL//zIOKS2UaxCSwQKBgQD29KtJkmlogSoOvRz1
            irWoCXKrKRh89zsQCqzDAxCYvijIDFRpGptv2VlBnrFnposi7oNShPHhMO9NLLcy
            /rFLiaOH3Ff4p+4aH0yzU/ELRMpLhnASd5pjytlRUhG2YviZJppXpBs5EkqGhiGS
            CjLrGpE/NIOZvSJAPa0Gy+aNMQKBgQDdXKu+rTg06kkByip69BAXMDbmO2272yBA
            oXhp8CPieqNHXsn8bi2L/jJ8SjJk7kTSeQ2O6RkFkNFs/Hgly/bhmwdk4UKLMsI3
            iXDJHaf156UFzlJdnaSjeQHqfdGYZCawH6FOrGtgeaa0AJ1W518AKGKtd6RizwvF
            gvZa/0JThwKBgQDRZufzK54LyjJyavMqVaLxSImeDuu/0xeW46ZYebnR+rSz5JiS
            wsf4kJE5ril53WNvjFV4XFiJfaf8haUWLZcCmUQ8CJ/jremc5ttv2Aa1anAqmGVh
            k18rrS6W4QM55zQrEOug0vtuak8dQJEot1MzvcRZltrcOt7XIDUeZTkqkQKBgGJa
            rZigIGwqeNvJtsxAqvGSo6HyX9RrDU6KMBVp9JfJg8jPThHR5nzgKhBdazy7PWsD
            DEomOc7mRJcXKk8o6/3bwxJIoCd3GyJP+AP0IE+7Z/BHdwNPavqTZo2I79q1DReO
            Rj7NjzwjcTVjXIVjciuAxi9rDGg2tHVCtjFCX3b7AoGBAPDdnnyu7J7jn4NotlUp
            4Zfiq1w0XOgEsPjliaOZf8i4Fr7wKw5npTo8QJsCM5UnEyEp0FdMhy/lY5wEWrYJ
            AE3hHOL3Rau3sHzEN6SevoX0lh/sWA67X5bD0AW4G7Dp0pZ4sxXSrv/8GXQypt39
            OcoBYzZErkq9WksMCp7L2Gn3
            -----END PRIVATE KEY-----
            """;

    private static final String CLIENT_CERTIFICATE = """
            -----BEGIN CERTIFICATE-----
            MIIDHzCCAgegAwIBAgIUOgKHCsDu8CyGLH9GGHeVKLAQKHIwDQYJKoZIhvcNAQEL
            BQAwFDESMBAGA1UEAwwJbG9jYWxob3N0MB4XDTI2MDcwNjEzNTkzOFoXDTM2MDcw
            MzEzNTkzOFowFDESMBAGA1UEAwwJbG9jYWxob3N0MIIBIjANBgkqhkiG9w0BAQEF
            AAOCAQ8AMIIBCgKCAQEAxWJsYSCQ0L+rzzmYK3q/kYsZ9HnbHiQzMhp9oDFHGpb8
            GHa0tSXFpUG+GZZ5Wn/bI1LeO2+dNO5KtaUT71KIIlRhHXol0qcxYVrLfSHAKd6A
            N15MUzOswLI8+fwgs21Vt3IAyHee9ie1hLSYJCX2eLn5b3s27A7euq4RUoCL4r9f
            I2UvPODYGnKZoiyZA4dlCy6WocdLTu89tn4k9V4gMPLZwqQJCL4kC3qdI9Xn4WlW
            1feQyRtBie9GDCy4WFC0f5CfAShoxXoj/YyKj3KPC88YKEpFbFoUJue6Ktj3tJ1b
            D7nr+n5Nprv7xhTJl0rWDIto1ud3kPb6BGr39FFI8QIDAQABo2kwZzAdBgNVHQ4E
            FgQU0bbgO7PjP1NVu71HYS0B+CoeRV8wHwYDVR0jBBgwFoAU0bbgO7PjP1NVu71H
            YS0B+CoeRV8wDwYDVR0TAQH/BAUwAwEB/zAUBgNVHREEDTALgglsb2NhbGhvc3Qw
            DQYJKoZIhvcNAQELBQADggEBAGSAJeRHIYd5+MBV92kBydvM9dmTWXBk5TiUYkEn
            2sBfNC5sg/dna4epmngaSxEGeUyCERacYp9BTO9xMyG49nEQxXneEE4vxmxzd3KM
            U/nvw1hoGnOs57UeUSkCPv+IGaLLv28/16DTE5RwENwvNx6phFNIhHnJWI2qTSrx
            O4osy4lvzmH5Zon9v0SlX2hJcrXcGiI37FAZpiGfcEJNp73RTmGa2b+QbL3Wa/mJ
            wkQ7cEx9dDQiAt35dcZd3vQ7Qe9C1t8byCcpy6RyabUJMI3ZMrVacHu9dSnAy/2f
            DqjDnwttOjBSXDC8pNe7Oidnd2TknunW1UmzMe8djYPFLG0=
            -----END CERTIFICATE-----
            """;

    private static final String CLIENT_PRIVATE_KEY = """
            -----BEGIN PRIVATE KEY-----
            MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDFYmxhIJDQv6vP
            OZgrer+Rixn0edseJDMyGn2gMUcalvwYdrS1JcWlQb4Zlnlaf9sjUt47b5007kq1
            pRPvUogiVGEdeiXSpzFhWst9IcAp3oA3XkxTM6zAsjz5/CCzbVW3cgDId572J7WE
            tJgkJfZ4uflvezbsDt66rhFSgIviv18jZS884NgacpmiLJkDh2ULLpahx0tO7z22
            fiT1XiAw8tnCpAkIviQLep0j1efhaVbV95DJG0GJ70YMLLhYULR/kJ8BKGjFeiP9
            jIqPco8LzxgoSkVsWhQm57oq2Pe0nVsPuev6fk2mu/vGFMmXStYMi2jW53eQ9voE
            avf0UUjxAgMBAAECggEAIJVLQ8tO7I6/a2FcC81jA0EdozxcXgDmzJ4P39pSnQKQ
            ke9CjGa0Vj/ghDPRS42wnIeo9NHLdzng7lRWUpriqxwXvfh0du4vmh0VW04vfRnk
            O3+fBNsSaOrjYvbqZQQm8rXoiC8q+AJ9Fe+D7++uXKaClRMXcWdRACsM4/whW2gm
            sqM51FE3HGTf0SqVsaP6Vvf83ax6uh/Qld/ZZETzmTiaFQB+BnB9YrKxGaE4KDS0
            8lXxrGdK2n3knjtVSEhB2jErnZ8p1eHQDit3ml3DzV/B6qXS3lcERnxqCIzXPQUy
            htvzRE2U86aMrX7TcX7VjbIo31GC/ks4VzDYbpTsmwKBgQDhjZnNK2ERZuePMmZh
            GSbYIj9tIfAMERNzCqjBYAjB2WR5Hih9ioJysXt/RG9X30bsCjekA6lgr+r1GDPF
            Kck54888G2tC583YaACZuLx5AnvsYZUWpRJ2KVuJ6ye8zNthYh6GnCs62IVMFDHm
            dGIWgj0X/kYlUzly5abroUsc1wKBgQDgB2c0sXYKXRQIoBvPuKUaZKxLPT6WIV37
            QlIuqGjhxmbLLvgzqzp0YQLHBW5+pzZBUDf92Qzy5mACJtZszsZ3lKryRAGeSWVm
            4Uq6/0LgwWk96pBHldyoKl99AEoxnuyLUtjz5casgQ9QmnWr3Ql/Mwr+Eq28rsY1
            gg1Uf6sHdwKBgD9l4sY1nDoc95dbbkV6oV18+uD8+hEaN6JNa7O2/58lMMWOgYkQ
            BqygUHssTDOh0biFArC6SsCfkeZEYEDaBvKbouNdPZ/3sfdS/GA4+dj1psmBgDt2
            OKM36r/wdiHuXveXcccaXFhVBdqljoUdT3jOMMfus97H7szp2io/EvLPAoGBAJtR
            5qbsxlcHE0Wro7yPWsCCvcVoT8UMmzkBUTMSBa1KvafvB5vjAiL/w0oypNngmkgM
            EtXHLygUusmjHZTQg18SzrYqmiig4FHErugY48gpsAqCXseSyio5PnzOv6HJelxZ
            8xqd2vFshS6v+BdQpy3uOJfQYW3oink8kntcDDqfAoGBAL8yAj95MVMvVA7mrcAB
            DbQRGtINC+Lc1qclSTYI8UML3mORs+Uk5R+rswT5cHAMTunFCi+u+267hOOch+0Y
            DbtkZ70DPd6OrydgTp6LM7FFBGm1t3yRJ4O4pOAaphlO35CQ0bY1ikwWgUBnIfjz
            F1mgxUp8sNsaLHb40v/uH1Kb
            -----END PRIVATE KEY-----
            """;
}
