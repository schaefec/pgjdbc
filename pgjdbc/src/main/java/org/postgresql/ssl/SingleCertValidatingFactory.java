/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.ssl;

import org.postgresql.util.GT;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.UUID;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * <p>Provides a SSLSocketFactory that authenticates the remote server against an explicit pre-shared
 * SSL certificate. This is more secure than using the NonValidatingFactory as it prevents "man in
 * the middle" attacks. It is also more secure than relying on a central CA signing your server's
 * certificate as it pins the server's certificate.</p>
 *
 * <p>This class requires a single String parameter specified by setting the connection property
 * <code>sslfactoryarg</code>. The value of this property is the PEM-encoded remote server's SSL
 * certificate.</p>
 *
 * <p>Where the certificate is loaded from is based upon the prefix of the <code>sslfactoryarg</code> property.
 * The following table lists the valid set of prefixes.</p>
 *
 * <table border="1" summary="Valid prefixes for sslfactoryarg">
 * <tr>
 *     <th>Prefix</th>
 *     <th>Example</th>
 *     <th>Explanation</th>
 * </tr>
 * <tr>
 *     <td><code>classpath:</code></td>
 *     <td><code>classpath:ssl/server.crt</code></td>
 *     <td>Loaded from the classpath.</td>
 * </tr>
 * <tr>
 *     <td><code>file:</code></td>
 *     <td><code>file:/foo/bar/server.crt</code></td>
 *     <td>Loaded from the filesystem.</td>
 * </tr>
 * <tr>
 *     <td><code>env:</code></td>
 *     <td><code>env:mydb_cert</code></td>
 *     <td>Loaded from string value of the <code>mydb_cert</code> environment variable.</td>
 * </tr>
 * <tr>
 *     <td><code>sys:</code></td>
 *     <td><code>sys:mydb_cert</code></td>
 *     <td>Loaded from string value of the <code>mydb_cert</code> system property.</td>
 * </tr>
 * <tr>
 *     <td><pre>-----BEGIN CERTIFICATE------</pre></td>
 *     <td>
 *         <pre>
 * -----BEGIN CERTIFICATE-----
 * MIIDQzCCAqygAwIBAgIJAOd1tlfiGoEoMA0GCSqGSIb3DQEBBQUAMHUxCzAJBgNV
 * [... truncated ...]
 * UCmmYqgiVkAGWRETVo+byOSDZ4swb10=
 * -----END CERTIFICATE-----
 *         </pre>
*      </td>
 *     <td>Loaded from string value of the argument.</td>
 * </tr>
 * </table>
 */

public class SingleCertValidatingFactory extends WrappedFactory {
  private static final String FILE_PREFIX = "file:";
  private static final String CLASSPATH_PREFIX = "classpath:";
  private static final String ENV_PREFIX = "env:";
  private static final String SYS_PROP_PREFIX = "sys:";

  public SingleCertValidatingFactory(String sslFactoryArg) throws GeneralSecurityException {
    if (sslFactoryArg == null || sslFactoryArg.equals("")) {
      throw new GeneralSecurityException(GT.tr("The sslfactoryarg property may not be empty."));
    }
    InputStream in = null;
    try {
      if (sslFactoryArg.startsWith(FILE_PREFIX)) {
        String path = sslFactoryArg.substring(FILE_PREFIX.length());
        in = new BufferedInputStream(new FileInputStream(path));
      } else if (sslFactoryArg.startsWith(CLASSPATH_PREFIX)) {
        String path = sslFactoryArg.substring(CLASSPATH_PREFIX.length());
        in = new BufferedInputStream(
            Thread.currentThread().getContextClassLoader().getResourceAsStream(path));
      } else if (sslFactoryArg.startsWith(ENV_PREFIX)) {
        String name = sslFactoryArg.substring(ENV_PREFIX.length());
        String cert = System.getenv(name);
        if (cert == null || "".equals(cert)) {
          throw new GeneralSecurityException(GT.tr(
              "The environment variable containing the server's SSL certificate must not be empty."));
        }
        in = new ByteArrayInputStream(cert.getBytes("UTF-8"));
      } else if (sslFactoryArg.startsWith(SYS_PROP_PREFIX)) {
        String name = sslFactoryArg.substring(SYS_PROP_PREFIX.length());
        String cert = System.getProperty(name);
        if (cert == null || "".equals(cert)) {
          throw new GeneralSecurityException(GT.tr(
              "The system property containing the server's SSL certificate must not be empty."));
        }
        in = new ByteArrayInputStream(cert.getBytes("UTF-8"));
      } else if (sslFactoryArg.startsWith("-----BEGIN CERTIFICATE-----")) {
        in = new ByteArrayInputStream(sslFactoryArg.getBytes("UTF-8"));
      } else {
        throw new GeneralSecurityException(GT.tr(
            "The sslfactoryarg property must start with the prefix file:, classpath:, env:, sys:, or -----BEGIN CERTIFICATE-----."));
      }

      KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
      // Note: KeyStore requires it be loaded even if you don't load anything into it:
      ks.load(null);
      CertificateFactory cf = CertificateFactory.getInstance("X509");
      X509Certificate cert = (X509Certificate) cf.generateCertificate(in);
      ks.setCertificateEntry(UUID.randomUUID().toString(), cert);
      TrustManagerFactory tmf =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(ks);

      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(null, tmf.getTrustManagers(), null);
      factory = ctx.getSocketFactory();
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      if (e instanceof GeneralSecurityException) {
        throw (GeneralSecurityException) e;
      }
      throw new GeneralSecurityException(GT.tr("An error occurred reading the certificate"), e);
    } finally {
      if (in != null) {
        try {
          in.close();
        } catch (Exception e2) {
          // ignore
        }
      }
    }
  }
}
