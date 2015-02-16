package com.emarsys.escher;

import com.emarsys.escher.util.DateTime;
import com.emarsys.escher.util.Hmac;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;

import javax.xml.bind.DatatypeConverter;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URLEncoder;
import java.util.*;
import java.util.function.BinaryOperator;
import java.util.function.Predicate;

class Helper {

    private static final char NEW_LINE = '\n';
    private static final String CHARSET = "UTF-8";

    private final Config config;


    public Helper (Config config) {
        this.config = config;
    }


    public String canonicalize(EscherRequest request, List<String> signedHeaders) throws EscherException {
        try {
            return request.getHttpMethod() + NEW_LINE +
                    request.getURI().toURL().getPath() + NEW_LINE +
                    canonicalizeQueryParameters(request) + NEW_LINE +
                    canonicalizeHeaders(request.getRequestHeaders(), signedHeaders) + NEW_LINE +
                    NEW_LINE +
                    signedHeaders(signedHeaders) + NEW_LINE +
                    Hmac.hash(request.getBody());
        } catch (MalformedURLException e) {
            throw new EscherException(e);
        }
    }


    private String canonicalizeQueryParameters(EscherRequest request) {
        return URLEncodedUtils.parse(request.getURI(), CHARSET)
                .stream()
                .map(this::queryParameterToString)
                .sorted()
                .reduce(byJoiningWith('&'))
                .orElseGet(() -> "");
    }


    private String queryParameterToString(NameValuePair entry) {
        try {
            return entry.getName() + "=" + URLEncoder.encode(entry.getValue(), CHARSET);
        } catch (UnsupportedEncodingException shouldNeverHappen) {
            throw new RuntimeException(shouldNeverHappen);
        }
    }


    private String canonicalizeHeaders(List<EscherRequest.Header> headers, List<String> signedHeaders) {
        return headers
                .stream()
                .filter(shouldHeaderBeSigned(signedHeaders))
                .map(header -> header.getFieldName().toLowerCase() + ":" + header.getFieldValue().trim())
                .sorted()
                .reduce(byJoiningWith(NEW_LINE))
                .orElseGet(() -> "");
    }


    private Predicate<EscherRequest.Header> shouldHeaderBeSigned(List<String> signedHeaders) {
        return header -> signedHeaders
                .stream()
                .anyMatch(signedHeader -> signedHeader.equalsIgnoreCase(header.getFieldName()));
    }


    private String signedHeaders(List<String> headers) {
        return headers
                .stream()
                .map(String::toLowerCase)
                .sorted()
                .reduce(byJoiningWith(';'))
                .orElseGet(() -> "");
    }


    private BinaryOperator<String> byJoiningWith(char separator) {
        return (s1, s2) -> s1 + separator + s2;
    }


    public String calculateStringToSign(String credentialScope, String canonicalizedRequest) throws EscherException {
        return config.getFullAlgorithm() + NEW_LINE
                + DateTime.toLongString(config.getDate()) + NEW_LINE
                + DateTime.toShortString(config.getDate()) + "/" + credentialScope + NEW_LINE
                + Hmac.hash(canonicalizedRequest);
    }


    public byte[] calculateSigningKey(String secret, String credentialScope) throws EscherException {
        byte[] key = Hmac.sign(config.getHashAlgo(), (config.getAlgoPrefix() + secret), DateTime.toShortString(config.getDate()));

        for (String credentialPart : credentialScope.split("/")) {
            key = Hmac.sign(config.getHashAlgo(), key, credentialPart);
        }

        return key;
    }


    public String calculateAuthHeader(String accessKeyId, String credentialScope, List<String> signedHeaders, String signature) {
        return config.getFullAlgorithm() +
                " Credential=" + credentials(accessKeyId, credentialScope) +
                ", SignedHeaders=" + signedHeaders.stream().reduce((s1, s2) -> s1 + ";" + s2).get().toLowerCase() +
                ", Signature=" + signature;
    }


    public String calculateSignature(byte[] signingKey, String stringToSign) throws EscherException {
        return DatatypeConverter.printHexBinary(Hmac.sign(config.getHashAlgo(), signingKey, stringToSign)).toLowerCase();
    }


    private String credentials(String accessKeyId, String credentialScope) {
        return accessKeyId + "/" + DateTime.toShortString(config.getDate()) + "/" + credentialScope;
    }


    public Map<String, String> calculateSigningParams(String accessKeyId, String credentialScope, int expires) {
        Map<String, String> params = new TreeMap<>();
        params.put("SignedHeaders", "host");
        params.put("Expires", Integer.toString(expires));
        params.put("Algorithm", config.getFullAlgorithm());
        params.put("Credentials", credentials(accessKeyId, credentialScope));
        params.put("Date", DateTime.toLongString(config.getDate()));

        return params;
    }


    public void addDateHeader(EscherRequest request) {
        boolean requestHasDateHeader = request.getRequestHeaders()
                .stream()
                .anyMatch(header -> header.getFieldName().equals(config.getDateHeaderName()));
        if (!requestHasDateHeader) {
            request.addHeader(config.getDateHeaderName(), DateTime.toLongString(config.getDate()));
        }
    }


    public void addAuthHeader(EscherRequest request, String header) {
        request.addHeader(config.getAuthHeaderName(), header);
    }


    public String parseHostHeader(EscherRequest request) throws EscherException {
        return findHeader(request, "host").getFieldValue();
    }

    public AuthHeader parseAuthHeader(EscherRequest request) throws EscherException {
        return AuthHeader.parse(findHeader(request, config.getAuthHeaderName()).getFieldValue());
    }


    public Date parseDateHeader(EscherRequest request) throws EscherException {
        return DateTime.parseLongString(findHeader(request, config.getDateHeaderName()).getFieldValue());
    }


    private EscherRequest.Header findHeader(EscherRequest request, String headerName) throws EscherException {
        try {

            return request.getRequestHeaders()
                    .stream()
                    .filter(header -> header.getFieldName().replace('_', '-').equalsIgnoreCase(headerName))
                    .findFirst().get();

        } catch (NoSuchElementException e) {
            throw new EscherException("Missing header: " + headerName);
        }
    }
}
