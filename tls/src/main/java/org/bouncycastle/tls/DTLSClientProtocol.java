package org.bouncycastle.tls;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Vector;

import org.bouncycastle.tls.crypto.TlsStreamSigner;
import org.bouncycastle.util.Arrays;

public class DTLSClientProtocol
    extends DTLSProtocol
{
    public DTLSClientProtocol()
    {
        super();
    }

    public DTLSTransport connect(TlsClient client, DatagramTransport transport)
        throws IOException
    {
        if (client == null)
        {
            throw new IllegalArgumentException("'client' cannot be null");
        }
        if (transport == null)
        {
            throw new IllegalArgumentException("'transport' cannot be null");
        }

        ClientHandshakeState state = new ClientHandshakeState();
        state.client = client;
        state.clientContext = new TlsClientContextImpl(client.getCrypto());

        client.init(state.clientContext);
        state.clientContext.handshakeBeginning(client);

        SecurityParameters securityParameters = state.clientContext.getSecurityParametersHandshake();
        securityParameters.extendedPadding = client.shouldUseExtendedPadding();

        TlsSession sessionToResume = state.client.getSessionToResume();
        if (sessionToResume != null && sessionToResume.isResumable())
        {
            SessionParameters sessionParameters = sessionToResume.exportSessionParameters();
            if (sessionParameters != null && sessionParameters.isExtendedMasterSecret())
            {
                state.tlsSession = sessionToResume;
                state.sessionParameters = sessionParameters;
            }
        }

        DTLSRecordLayer recordLayer = new DTLSRecordLayer(state.clientContext, state.client, transport);
        client.notifyCloseHandle(recordLayer);

        try
        {
            return clientHandshake(state, recordLayer);
        }
        catch (TlsFatalAlert fatalAlert)
        {
            abortClientHandshake(state, recordLayer, fatalAlert.getAlertDescription());
            throw fatalAlert;
        }
        catch (IOException e)
        {
            abortClientHandshake(state, recordLayer, AlertDescription.internal_error);
            throw e;
        }
        catch (RuntimeException e)
        {
            abortClientHandshake(state, recordLayer, AlertDescription.internal_error);
            throw new TlsFatalAlert(AlertDescription.internal_error, e);
        }
        finally
        {
            securityParameters.clear();
        }
    }

    protected void abortClientHandshake(ClientHandshakeState state, DTLSRecordLayer recordLayer, short alertDescription)
    {
        recordLayer.fail(alertDescription);
        invalidateSession(state);
    }

    protected DTLSTransport clientHandshake(ClientHandshakeState state, DTLSRecordLayer recordLayer)
        throws IOException
    {
        SecurityParameters securityParameters = state.clientContext.getSecurityParametersHandshake();
        DTLSReliableHandshake handshake = new DTLSReliableHandshake(state.clientContext, recordLayer,
            state.client.getHandshakeTimeoutMillis(), null);

        byte[] clientHelloBody = generateClientHello(state);

        recordLayer.setWriteVersion(ProtocolVersion.DTLSv10);

        handshake.sendMessage(HandshakeType.client_hello, clientHelloBody);

        DTLSReliableHandshake.Message serverMessage = handshake.receiveMessage();

        while (serverMessage.getType() == HandshakeType.hello_verify_request)
        {
            ProtocolVersion recordLayerVersion = recordLayer.getReadVersion();
            ProtocolVersion client_version = state.clientContext.getClientVersion();

            /*
             * RFC 6347 4.2.1 DTLS 1.2 server implementations SHOULD use DTLS version 1.0 regardless of
             * the version of TLS that is expected to be negotiated. DTLS 1.2 and 1.0 clients MUST use
             * the version solely to indicate packet formatting (which is the same in both DTLS 1.2 and
             * 1.0) and not as part of version negotiation.
             */
            if (!recordLayerVersion.isEqualOrEarlierVersionOf(client_version))
            {
                throw new TlsFatalAlert(AlertDescription.illegal_parameter);
            }

            recordLayer.setReadVersion(null);

            byte[] cookie = processHelloVerifyRequest(state, serverMessage.getBody());
            byte[] patched = patchClientHelloWithCookie(clientHelloBody, cookie);

            handshake.resetAfterHelloVerifyRequestClient();
            handshake.sendMessage(HandshakeType.client_hello, patched);

            serverMessage = handshake.receiveMessage();
        }

        if (serverMessage.getType() == HandshakeType.server_hello)
        {
            ProtocolVersion recordLayerVersion = recordLayer.getReadVersion();
            reportServerVersion(state, recordLayerVersion);
            recordLayer.setWriteVersion(recordLayerVersion);

            processServerHello(state, serverMessage.getBody());
        }
        else
        {
            throw new TlsFatalAlert(AlertDescription.unexpected_message);
        }

        handshake.notifyPRFDetermined();

        applyMaxFragmentLengthExtension(recordLayer, securityParameters.getMaxFragmentLength());

        if (state.resumedSession)
        {
            securityParameters.masterSecret = state.clientContext.getCrypto().adoptSecret(state.sessionParameters.getMasterSecret());
            recordLayer.initPendingEpoch(TlsUtils.initCipher(state.clientContext));

            // NOTE: Calculated exclusive of the actual Finished message from the server
            securityParameters.peerVerifyData = createVerifyData(state.clientContext, handshake, true);
            processFinished(handshake.receiveMessageBody(HandshakeType.finished), securityParameters.getPeerVerifyData());

            // NOTE: Calculated exclusive of the Finished message itself
            securityParameters.localVerifyData = createVerifyData(state.clientContext, handshake, false);
            handshake.sendMessage(HandshakeType.finished, securityParameters.getLocalVerifyData());

            handshake.finish();

            if (securityParameters.isExtendedMasterSecret())
            {
                securityParameters.tlsUnique = securityParameters.getPeerVerifyData();
            }

            state.clientContext.handshakeComplete(state.client, state.tlsSession);

            recordLayer.initHeartbeat(state.heartbeat, HeartbeatMode.peer_allowed_to_send == state.heartbeatPolicy);

            return new DTLSTransport(recordLayer);
        }

        invalidateSession(state);

        state.tlsSession = TlsUtils.importSession(securityParameters.getSessionID(), null);
        state.sessionParameters = null;

        serverMessage = handshake.receiveMessage();

        if (serverMessage.getType() == HandshakeType.supplemental_data)
        {
            processServerSupplementalData(state, serverMessage.getBody());
            serverMessage = handshake.receiveMessage();
        }
        else
        {
            state.client.processServerSupplementalData(null);
        }

        state.keyExchange = TlsUtils.initKeyExchangeClient(state.clientContext, state.client);

        if (serverMessage.getType() == HandshakeType.certificate)
        {
            processServerCertificate(state, serverMessage.getBody());
            serverMessage = handshake.receiveMessage();
        }
        else
        {
            // Okay, Certificate is optional
            state.authentication = null;
        }

        if (serverMessage.getType() == HandshakeType.certificate_status)
        {
            processCertificateStatus(state, serverMessage.getBody());
            serverMessage = handshake.receiveMessage();
        }
        else
        {
            // Okay, CertificateStatus is optional
        }

        TlsUtils.processServerCertificate(state.clientContext, state.client, state.certificateStatus, state.keyExchange,
            state.authentication, state.clientExtensions, state.serverExtensions);

        if (serverMessage.getType() == HandshakeType.server_key_exchange)
        {
            processServerKeyExchange(state, serverMessage.getBody());
            serverMessage = handshake.receiveMessage();
        }
        else
        {
            // Okay, ServerKeyExchange is optional
            state.keyExchange.skipServerKeyExchange();
        }

        if (serverMessage.getType() == HandshakeType.certificate_request)
        {
            processCertificateRequest(state, serverMessage.getBody());

            /*
             * TODO Give the client a chance to immediately select the CertificateVerify hash
             * algorithm here to avoid tracking the other hash algorithms unnecessarily?
             */
            TlsUtils.trackHashAlgorithms(handshake.getHandshakeHash(),
                state.certificateRequest.getSupportedSignatureAlgorithms());

            serverMessage = handshake.receiveMessage();
        }
        else
        {
            // Okay, CertificateRequest is optional
        }

        if (serverMessage.getType() == HandshakeType.server_hello_done)
        {
            if (serverMessage.getBody().length != 0)
            {
                throw new TlsFatalAlert(AlertDescription.decode_error);
            }
        }
        else
        {
            throw new TlsFatalAlert(AlertDescription.unexpected_message);
        }

        Vector clientSupplementalData = state.client.getClientSupplementalData();
        if (clientSupplementalData != null)
        {
            byte[] supplementalDataBody = generateSupplementalData(clientSupplementalData);
            handshake.sendMessage(HandshakeType.supplemental_data, supplementalDataBody);
        }

        if (null != state.certificateRequest)
        {
            state.clientCredentials = TlsProtocol.validateCredentials(
                state.authentication.getClientCredentials(state.certificateRequest));

            /*
             * RFC 5246 If no suitable certificate is available, the client MUST send a certificate
             * message containing no certificates.
             * 
             * NOTE: In previous RFCs, this was SHOULD instead of MUST.
             */

            Certificate clientCertificate = null;
            if (null != state.clientCredentials)
            {
                clientCertificate = state.clientCredentials.getCertificate();
            }

            sendCertificateMessage(state.clientContext, handshake, clientCertificate, null);
        }

        TlsCredentialedSigner credentialedSigner = null;
        TlsStreamSigner streamSigner = null;

        if (null != state.clientCredentials)
        {
            state.keyExchange.processClientCredentials(state.clientCredentials);
            
            if (state.clientCredentials instanceof TlsCredentialedSigner)
            {
                credentialedSigner = (TlsCredentialedSigner)state.clientCredentials;
                streamSigner = credentialedSigner.getStreamSigner();
            }
        }
        else
        {
            state.keyExchange.skipClientCredentials();
        }

        boolean forceBuffering = streamSigner != null;
        TlsUtils.sealHandshakeHash(state.clientContext, handshake.getHandshakeHash(), forceBuffering);

        byte[] clientKeyExchangeBody = generateClientKeyExchange(state);
        handshake.sendMessage(HandshakeType.client_key_exchange, clientKeyExchangeBody);

        TlsHandshakeHash prepareFinishHash = handshake.prepareToFinish();
        securityParameters.sessionHash = TlsUtils.getCurrentPRFHash(prepareFinishHash);

        TlsProtocol.establishMasterSecret(state.clientContext, state.keyExchange);
        recordLayer.initPendingEpoch(TlsUtils.initCipher(state.clientContext));

        if (credentialedSigner != null)
        {
            DigitallySigned certificateVerify = TlsUtils.generateCertificateVerify(state.clientContext,
                credentialedSigner, streamSigner, prepareFinishHash);
            byte[] certificateVerifyBody = generateCertificateVerify(state, certificateVerify);
            handshake.sendMessage(HandshakeType.certificate_verify, certificateVerifyBody);
        }

        // NOTE: Calculated exclusive of the Finished message itself
        securityParameters.localVerifyData = createVerifyData(state.clientContext, handshake, false);
        handshake.sendMessage(HandshakeType.finished, securityParameters.getLocalVerifyData());

        if (state.expectSessionTicket)
        {
            serverMessage = handshake.receiveMessage();
            if (serverMessage.getType() == HandshakeType.new_session_ticket)
            {
                processNewSessionTicket(state, serverMessage.getBody());
            }
            else
            {
                throw new TlsFatalAlert(AlertDescription.unexpected_message);
            }
        }

        // NOTE: Calculated exclusive of the actual Finished message from the server
        securityParameters.peerVerifyData = createVerifyData(state.clientContext, handshake, true);
        processFinished(handshake.receiveMessageBody(HandshakeType.finished), securityParameters.getPeerVerifyData());

        handshake.finish();

        state.sessionParameters = new SessionParameters.Builder()
            .setCipherSuite(securityParameters.getCipherSuite())
            .setCompressionAlgorithm(securityParameters.getCompressionAlgorithm())
            .setExtendedMasterSecret(securityParameters.isExtendedMasterSecret())
            .setLocalCertificate(securityParameters.getLocalCertificate())
            .setMasterSecret(state.clientContext.getCrypto().adoptSecret(securityParameters.getMasterSecret()))
            .setNegotiatedVersion(securityParameters.getNegotiatedVersion())
            .setPeerCertificate(securityParameters.getPeerCertificate())
            .setPSKIdentity(securityParameters.getPSKIdentity())
            .setSRPIdentity(securityParameters.getSRPIdentity())
            // TODO Consider filtering extensions that aren't relevant to resumed sessions
            .setServerExtensions(state.serverExtensions)
            .build();

        state.tlsSession = TlsUtils.importSession(state.tlsSession.getSessionID(), state.sessionParameters);

        securityParameters.tlsUnique = securityParameters.getLocalVerifyData();

        state.clientContext.handshakeComplete(state.client, state.tlsSession);

        recordLayer.initHeartbeat(state.heartbeat, HeartbeatMode.peer_allowed_to_send == state.heartbeatPolicy);

        return new DTLSTransport(recordLayer);
    }

    protected byte[] generateCertificateVerify(ClientHandshakeState state, DigitallySigned certificateVerify)
        throws IOException
    {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        certificateVerify.encode(buf);
        return buf.toByteArray();
    }

    protected byte[] generateClientHello(ClientHandshakeState state)
        throws IOException
    {
        TlsClientContextImpl context = state.clientContext;
        SecurityParameters securityParameters = context.getSecurityParametersHandshake();

        context.setClientSupportedVersions(state.client.getProtocolVersions());

        ProtocolVersion client_version = ProtocolVersion.getLatestDTLS(context.getClientSupportedVersions());
        if (!ProtocolVersion.isSupportedDTLSVersion(client_version))
        {
            throw new TlsFatalAlert(AlertDescription.internal_error);
        }

        context.setClientVersion(client_version);

        byte[] session_id = TlsUtils.getSessionID(state.tlsSession);

        boolean fallback = state.client.isFallback();

        state.offeredCipherSuites = state.client.getCipherSuites();

        if (session_id.length > 0 && state.sessionParameters != null)
        {
            /*
             * NOTE: If we ever enable session resumption without extended_master_secret, then
             * renegotiation MUST be disabled (see RFC 7627 5.4).
             */
            if (!state.sessionParameters.isExtendedMasterSecret()
                || !Arrays.contains(state.offeredCipherSuites, state.sessionParameters.getCipherSuite())
                || CompressionMethod._null != state.sessionParameters.getCompressionAlgorithm())
            {
                session_id = TlsUtils.EMPTY_BYTES;
            }
        }

        state.clientExtensions = TlsExtensionsUtils.ensureExtensionsInitialised(state.client.getClientExtensions());

        ProtocolVersion legacy_version = client_version;
        if (client_version.isLaterVersionOf(ProtocolVersion.DTLSv12))
        {
            legacy_version = ProtocolVersion.DTLSv12;

            TlsExtensionsUtils.addSupportedVersionsExtensionClient(state.clientExtensions,
                context.getClientSupportedVersions());
        }

        securityParameters.clientServerNames = TlsExtensionsUtils.getServerNameExtensionClient(state.clientExtensions);

        if (TlsUtils.isSignatureAlgorithmsExtensionAllowed(client_version))
        {
            securityParameters.clientSigAlgs = TlsExtensionsUtils.getSignatureAlgorithmsExtension(state.clientExtensions);
            securityParameters.clientSigAlgsCert = TlsExtensionsUtils.getSignatureAlgorithmsCertExtension(state.clientExtensions);
        }

        securityParameters.clientSupportedGroups = TlsExtensionsUtils.getSupportedGroupsExtension(state.clientExtensions);

        state.clientAgreements = TlsUtils.addEarlyKeySharesToClientHello(state.clientContext, state.client, state.clientExtensions);

        TlsExtensionsUtils.addExtendedMasterSecretExtension(state.clientExtensions);

        securityParameters.clientRandom = TlsProtocol.createRandomBlock(state.client.shouldUseGMTUnixTime(), state.clientContext);

        // Cipher Suites (and SCSV)
        {
            /*
             * RFC 5746 3.4. The client MUST include either an empty "renegotiation_info" extension,
             * or the TLS_EMPTY_RENEGOTIATION_INFO_SCSV signaling cipher suite value in the
             * ClientHello. Including both is NOT RECOMMENDED.
             */
            boolean noRenegExt = (null == TlsUtils.getExtensionData(state.clientExtensions, TlsProtocol.EXT_RenegotiationInfo));
            boolean noRenegSCSV = !Arrays.contains(state.offeredCipherSuites, CipherSuite.TLS_EMPTY_RENEGOTIATION_INFO_SCSV);

            if (noRenegExt && noRenegSCSV)
            {
                state.offeredCipherSuites = Arrays.append(state.offeredCipherSuites, CipherSuite.TLS_EMPTY_RENEGOTIATION_INFO_SCSV);
            }
        }

        /* (Fallback SCSV)
         * RFC 7507 4. If a client sends a ClientHello.client_version containing a lower value
         * than the latest (highest-valued) version supported by the client, it SHOULD include
         * the TLS_FALLBACK_SCSV cipher suite value in ClientHello.cipher_suites [..]. (The
         * client SHOULD put TLS_FALLBACK_SCSV after all cipher suites that it actually intends
         * to negotiate.)
         */
        if (fallback && !Arrays.contains(state.offeredCipherSuites, CipherSuite.TLS_FALLBACK_SCSV))
        {
            state.offeredCipherSuites = Arrays.append(state.offeredCipherSuites, CipherSuite.TLS_FALLBACK_SCSV);
        }

        // Heartbeats
        {
            state.heartbeat = state.client.getHeartbeat();
            state.heartbeatPolicy = state.client.getHeartbeatPolicy();

            if (null != state.heartbeat || HeartbeatMode.peer_allowed_to_send == state.heartbeatPolicy)
            {
                TlsExtensionsUtils.addHeartbeatExtension(state.clientExtensions, new HeartbeatExtension(state.heartbeatPolicy));
            }
        }



        ClientHello clientHello = new ClientHello(legacy_version, securityParameters.getClientRandom(), session_id,
            TlsUtils.EMPTY_BYTES, state.offeredCipherSuites, state.clientExtensions);

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        clientHello.encode(state.clientContext, buf);
        return buf.toByteArray();
    }

    protected byte[] generateClientKeyExchange(ClientHandshakeState state)
        throws IOException
    {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        state.keyExchange.generateClientKeyExchange(buf);
        return buf.toByteArray();
    }

    protected void invalidateSession(ClientHandshakeState state)
    {
        if (state.sessionParameters != null)
        {
            state.sessionParameters.clear();
            state.sessionParameters = null;
        }

        if (state.tlsSession != null)
        {
            state.tlsSession.invalidate();
            state.tlsSession = null;
        }
    }

    protected void processCertificateRequest(ClientHandshakeState state, byte[] body)
        throws IOException
    {
        if (state.authentication == null)
        {
            /*
             * RFC 2246 7.4.4. It is a fatal handshake_failure alert for an anonymous server to
             * request client identification.
             */
            throw new TlsFatalAlert(AlertDescription.handshake_failure);
        }

        ByteArrayInputStream buf = new ByteArrayInputStream(body);

        state.certificateRequest = CertificateRequest.parse(state.clientContext, buf);

        TlsProtocol.assertEmpty(buf);

        state.certificateRequest = TlsUtils.validateCertificateRequest(state.certificateRequest, state.keyExchange);
    }

    protected void processCertificateStatus(ClientHandshakeState state, byte[] body)
        throws IOException
    {
        if (!state.allowCertificateStatus)
        {
            /*
             * RFC 3546 3.6. If a server returns a "CertificateStatus" message, then the
             * server MUST have included an extension of type "status_request" with empty
             * "extension_data" in the extended server hello..
             */
            throw new TlsFatalAlert(AlertDescription.unexpected_message);
        }

        ByteArrayInputStream buf = new ByteArrayInputStream(body);

        state.certificateStatus = CertificateStatus.parse(buf);

        TlsProtocol.assertEmpty(buf);
    }

    protected byte[] processHelloVerifyRequest(ClientHandshakeState state, byte[] body)
        throws IOException
    {
        ByteArrayInputStream buf = new ByteArrayInputStream(body);

        ProtocolVersion server_version = TlsUtils.readVersion(buf);

        /*
         * RFC 6347 This specification increases the cookie size limit to 255 bytes for greater
         * future flexibility. The limit remains 32 for previous versions of DTLS.
         */
        int maxCookieLength = ProtocolVersion.DTLSv12.isEqualOrEarlierVersionOf(server_version) ? 255 : 32;

        byte[] cookie = TlsUtils.readOpaque8(buf, 0, maxCookieLength);

        TlsProtocol.assertEmpty(buf);

        // TODO Seems this behaviour is not yet in line with OpenSSL for DTLS 1.2
//        reportServerVersion(state, server_version);
        if (!server_version.isEqualOrEarlierVersionOf(state.clientContext.getClientVersion()))
        {
            throw new TlsFatalAlert(AlertDescription.illegal_parameter);
        }

        return cookie;
    }

    protected void processNewSessionTicket(ClientHandshakeState state, byte[] body)
        throws IOException
    {
        ByteArrayInputStream buf = new ByteArrayInputStream(body);

        NewSessionTicket newSessionTicket = NewSessionTicket.parse(buf);

        TlsProtocol.assertEmpty(buf);

        state.client.notifyNewSessionTicket(newSessionTicket);
    }

    protected void processServerCertificate(ClientHandshakeState state, byte[] body)
        throws IOException
    {
        TlsUtils.receiveServerCertificate(state.clientContext, new ByteArrayInputStream(body));

        state.authentication = state.client.getAuthentication();
        if (null == state.authentication)
        {
            throw new TlsFatalAlert(AlertDescription.internal_error);
        }
    }

    protected void processServerHello(ClientHandshakeState state, byte[] body)
        throws IOException
    {
        ByteArrayInputStream buf = new ByteArrayInputStream(body);

        ServerHello serverHello = ServerHello.parse(buf);
        ProtocolVersion server_version = serverHello.getVersion();

        state.serverExtensions = serverHello.getExtensions();



        SecurityParameters securityParameters = state.clientContext.getSecurityParametersHandshake();

        // TODO[dtls13] Check supported_version extension for negotiated version

        reportServerVersion(state, server_version);

        securityParameters.serverRandom = serverHello.getRandom();

        if (!state.clientContext.getClientVersion().equals(server_version))
        {
            TlsUtils.checkDowngradeMarker(server_version, securityParameters.getServerRandom());
        }

        {
            byte[] selectedSessionID = serverHello.getSessionID();
            securityParameters.sessionID = selectedSessionID;
            state.client.notifySessionID(selectedSessionID);
            state.resumedSession = selectedSessionID.length > 0 && state.tlsSession != null
                && Arrays.areEqual(selectedSessionID, state.tlsSession.getSessionID());
        }

        /*
         * Find out which CipherSuite the server has chosen and check that it was one of the offered
         * ones, and is a valid selection for the negotiated version.
         */
        {
            int selectedCipherSuite = serverHello.getCipherSuite();
            if (!Arrays.contains(state.offeredCipherSuites, selectedCipherSuite)
                || selectedCipherSuite == CipherSuite.TLS_NULL_WITH_NULL_NULL
                || CipherSuite.isSCSV(selectedCipherSuite)
                || !TlsUtils.isValidCipherSuiteForVersion(selectedCipherSuite, state.clientContext.getServerVersion()))
            {
                throw new TlsFatalAlert(AlertDescription.illegal_parameter);
            }
            securityParameters.cipherSuite = validateSelectedCipherSuite(selectedCipherSuite,
                AlertDescription.illegal_parameter);
            state.client.notifySelectedCipherSuite(selectedCipherSuite);
        }

        /*
         * RFC3546 2.2 The extended server hello message format MAY be sent in place of the server
         * hello message when the client has requested extended functionality via the extended
         * client hello message specified in Section 2.1. ... Note that the extended server hello
         * message is only sent in response to an extended client hello message. This prevents the
         * possibility that the extended server hello message could "break" existing TLS 1.0
         * clients.
         */

        /*
         * TODO RFC 3546 2.3 If [...] the older session is resumed, then the server MUST ignore
         * extensions appearing in the client hello, and send a server hello containing no
         * extensions.
         */

        /*
         * RFC 7627 4. Clients and servers SHOULD NOT accept handshakes that do not use the extended
         * master secret [..]. (and see 5.2, 5.3)
         */
        securityParameters.extendedMasterSecret = TlsExtensionsUtils.hasExtendedMasterSecretExtension(state.serverExtensions);

        if (!securityParameters.isExtendedMasterSecret()
            && (state.resumedSession || state.client.requiresExtendedMasterSecret()))
        {
            throw new TlsFatalAlert(AlertDescription.handshake_failure);
        }

        /*
         * RFC 3546 2.2 Note that the extended server hello message is only sent in response to an
         * extended client hello message. However, see RFC 5746 exception below. We always include
         * the SCSV, so an Extended Server Hello is always allowed.
         */
        if (state.serverExtensions != null)
        {
            Enumeration e = state.serverExtensions.keys();
            while (e.hasMoreElements())
            {
                Integer extType = (Integer)e.nextElement();

                /*
                 * RFC 5746 3.6. Note that sending a "renegotiation_info" extension in response to a
                 * ClientHello containing only the SCSV is an explicit exception to the prohibition
                 * in RFC 5246, Section 7.4.1.4, on the server sending unsolicited extensions and is
                 * only allowed because the client is signaling its willingness to receive the
                 * extension via the TLS_EMPTY_RENEGOTIATION_INFO_SCSV SCSV.
                 */
                if (extType.equals(TlsProtocol.EXT_RenegotiationInfo))
                {
                    continue;
                }

                /*
                 * RFC 5246 7.4.1.4 An extension type MUST NOT appear in the ServerHello unless the
                 * same extension type appeared in the corresponding ClientHello. If a client
                 * receives an extension type in ServerHello that it did not request in the
                 * associated ClientHello, it MUST abort the handshake with an unsupported_extension
                 * fatal alert.
                 */
                if (null == TlsUtils.getExtensionData(state.clientExtensions, extType))
                {
                    throw new TlsFatalAlert(AlertDescription.unsupported_extension);
                }

                /*
                 * RFC 3546 2.3. If [...] the older session is resumed, then the server MUST ignore
                 * extensions appearing in the client hello, and send a server hello containing no
                 * extensions[.]
                 */
                if (state.resumedSession)
                {
                    // TODO[compat-gnutls] GnuTLS test server sends server extensions e.g. ec_point_formats
                    // TODO[compat-openssl] OpenSSL test server sends server extensions e.g. ec_point_formats
                    // TODO[compat-polarssl] PolarSSL test server sends server extensions e.g. ec_point_formats
//                    throw new TlsFatalAlert(AlertDescription.illegal_parameter);
                }
            }
        }

        /*
         * RFC 5746 3.4. Client Behavior: Initial Handshake
         */
        {
            /*
             * When a ServerHello is received, the client MUST check if it includes the
             * "renegotiation_info" extension:
             */
            byte[] renegExtData = TlsUtils.getExtensionData(state.serverExtensions, TlsProtocol.EXT_RenegotiationInfo);
            if (renegExtData != null)
            {
                /*
                 * If the extension is present, set the secure_renegotiation flag to TRUE. The
                 * client MUST then verify that the length of the "renegotiated_connection"
                 * field is zero, and if it is not, MUST abort the handshake (by sending a fatal
                 * handshake_failure alert).
                 */
                securityParameters.secureRenegotiation = true;

                if (!Arrays.constantTimeAreEqual(renegExtData,
                    TlsProtocol.createRenegotiationInfo(TlsUtils.EMPTY_BYTES)))
                {
                    throw new TlsFatalAlert(AlertDescription.handshake_failure);
                }
            }
        }

        // TODO[compat-gnutls] GnuTLS test server fails to send renegotiation_info extension when resuming
        state.client.notifySecureRenegotiation(securityParameters.isSecureRenegotiation());

        /*
         * RFC 7301 3.1. When session resumption or session tickets [...] are used, the previous
         * contents of this extension are irrelevant, and only the values in the new handshake
         * messages are considered.
         */
        securityParameters.applicationProtocol = TlsExtensionsUtils.getALPNExtensionServer(state.serverExtensions);
        securityParameters.applicationProtocolSet = true;

        // Heartbeats
        {
            HeartbeatExtension heartbeatExtension = TlsExtensionsUtils.getHeartbeatExtension(state.serverExtensions);
            if (null == heartbeatExtension)
            {
                state.heartbeat = null;
                state.heartbeatPolicy = HeartbeatMode.peer_not_allowed_to_send;
            }
            else if (HeartbeatMode.peer_allowed_to_send != heartbeatExtension.getMode())
            {
                state.heartbeat = null;
            }
        }



        Hashtable sessionClientExtensions = state.clientExtensions, sessionServerExtensions = state.serverExtensions;

        if (state.resumedSession)
        {
            if (securityParameters.getCipherSuite() != state.sessionParameters.getCipherSuite()
                || CompressionMethod._null != state.sessionParameters.getCompressionAlgorithm()
                || !server_version.equals(state.sessionParameters.getNegotiatedVersion()))
            {
                throw new TlsFatalAlert(AlertDescription.illegal_parameter);
            }

            sessionClientExtensions = null;
            sessionServerExtensions = state.sessionParameters.readServerExtensions();
        }

        if (sessionServerExtensions != null && !sessionServerExtensions.isEmpty())
        {
            {
                /*
                 * RFC 7366 3. If a server receives an encrypt-then-MAC request extension from a client
                 * and then selects a stream or Authenticated Encryption with Associated Data (AEAD)
                 * ciphersuite, it MUST NOT send an encrypt-then-MAC response extension back to the
                 * client.
                 */
                boolean serverSentEncryptThenMAC = TlsExtensionsUtils.hasEncryptThenMACExtension(sessionServerExtensions);
                if (serverSentEncryptThenMAC && !TlsUtils.isBlockCipherSuite(securityParameters.getCipherSuite()))
                {
                    throw new TlsFatalAlert(AlertDescription.illegal_parameter);
                }
                securityParameters.encryptThenMAC = serverSentEncryptThenMAC;
            }

            securityParameters.maxFragmentLength = evaluateMaxFragmentLengthExtension(state.resumedSession,
                sessionClientExtensions, sessionServerExtensions, AlertDescription.illegal_parameter);

            securityParameters.truncatedHMac = TlsExtensionsUtils.hasTruncatedHMacExtension(sessionServerExtensions);

            /*
             * TODO It's surprising that there's no provision to allow a 'fresh' CertificateStatus to be
             * sent in a session resumption handshake.
             */
            state.allowCertificateStatus = !state.resumedSession
                && TlsUtils.hasExpectedEmptyExtensionData(sessionServerExtensions, TlsExtensionsUtils.EXT_status_request,
                    AlertDescription.illegal_parameter);

            state.expectSessionTicket = !state.resumedSession
                && TlsUtils.hasExpectedEmptyExtensionData(sessionServerExtensions, TlsProtocol.EXT_SessionTicket,
                    AlertDescription.illegal_parameter);
        }

        if (sessionClientExtensions != null)
        {
            state.client.processServerExtensions(sessionServerExtensions);
        }

        securityParameters.prfAlgorithm = TlsProtocol.getPRFAlgorithm(state.clientContext,
            securityParameters.getCipherSuite());

        /*
         * RFC 5246 7.4.9. Any cipher suite which does not explicitly specify verify_data_length has
         * a verify_data_length equal to 12. This includes all existing cipher suites.
         */
        securityParameters.verifyDataLength = 12;
    }

    protected void processServerKeyExchange(ClientHandshakeState state, byte[] body)
        throws IOException
    {
        ByteArrayInputStream buf = new ByteArrayInputStream(body);

        state.keyExchange.processServerKeyExchange(buf);

        TlsProtocol.assertEmpty(buf);
    }

    protected void processServerSupplementalData(ClientHandshakeState state, byte[] body)
        throws IOException
    {
        ByteArrayInputStream buf = new ByteArrayInputStream(body);
        Vector serverSupplementalData = TlsProtocol.readSupplementalDataMessage(buf);
        state.client.processServerSupplementalData(serverSupplementalData);
    }

    protected void reportServerVersion(ClientHandshakeState state, ProtocolVersion server_version)
        throws IOException
    {
        TlsClientContextImpl context = state.clientContext;
        ProtocolVersion currentServerVersion = context.getServerVersion();
        if (null == currentServerVersion)
        {
            if (!ProtocolVersion.isSupportedDTLSVersion(server_version)
                || !ProtocolVersion.contains(context.getClientSupportedVersions(), server_version))
            {
                throw new TlsFatalAlert(AlertDescription.illegal_parameter);
            }

            context.getSecurityParametersHandshake().negotiatedVersion = server_version;
            state.client.notifyServerVersion(server_version);
        }
        else if (!currentServerVersion.equals(server_version))
        {
            throw new TlsFatalAlert(AlertDescription.illegal_parameter);
        }
    }

    protected static byte[] patchClientHelloWithCookie(byte[] clientHelloBody, byte[] cookie)
        throws IOException
    {
        int sessionIDPos = 34;
        int sessionIDLength = TlsUtils.readUint8(clientHelloBody, sessionIDPos);

        int cookieLengthPos = sessionIDPos + 1 + sessionIDLength;
        int cookiePos = cookieLengthPos + 1;

        byte[] patched = new byte[clientHelloBody.length + cookie.length];
        System.arraycopy(clientHelloBody, 0, patched, 0, cookieLengthPos);
        TlsUtils.checkUint8(cookie.length);
        TlsUtils.writeUint8(cookie.length, patched, cookieLengthPos);
        System.arraycopy(cookie, 0, patched, cookiePos, cookie.length);
        System.arraycopy(clientHelloBody, cookiePos, patched, cookiePos + cookie.length, clientHelloBody.length
            - cookiePos);

        return patched;
    }

    protected static class ClientHandshakeState
    {
        TlsClient client = null;
        TlsClientContextImpl clientContext = null;
        TlsSession tlsSession = null;
        SessionParameters sessionParameters = null;
        SessionParameters.Builder sessionParametersBuilder = null;
        int[] offeredCipherSuites = null;
        Hashtable clientExtensions = null;
        Hashtable serverExtensions = null;
        boolean resumedSession = false;
        boolean allowCertificateStatus = false;
        boolean expectSessionTicket = false;
        Hashtable clientAgreements = null;
        TlsKeyExchange keyExchange = null;
        TlsAuthentication authentication = null;
        CertificateStatus certificateStatus = null;
        CertificateRequest certificateRequest = null;
        TlsCredentials clientCredentials = null;
        TlsHeartbeat heartbeat = null;
        short heartbeatPolicy = HeartbeatMode.peer_not_allowed_to_send;
    }
}
