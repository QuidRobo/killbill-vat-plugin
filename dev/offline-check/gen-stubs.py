#!/usr/bin/env python3
"""
Generates minimal Kill Bill API stubs for the offline compile check.

These are NOT the real API. Signatures mirror killbill-api 0.54.0, killbill-plugin-api 0.27.3,
killbill-base-plugin 5.1.9 and killbill-platform 0.41.18; every method body returns null.
They exist so the plugin can be type-checked where Maven Central is unreachable.

If a stub disagrees with the real API, the real API is right: fix the stub.
"""
import os, sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), 'stubs')

STUBS = {
    "javax/servlet/Servlet.java": r'''package javax.servlet;

public interface Servlet { }
''',
    "javax/servlet/ServletRequest.java": r'''package javax.servlet;

public interface ServletRequest {
    Object getAttribute(String name);
    String getParameter(String name);
}
''',
    "javax/servlet/ServletResponse.java": r'''package javax.servlet;

public interface ServletResponse {
    void setContentType(String type);
}
''',
    "javax/servlet/http/HttpServletRequest.java": r'''package javax.servlet.http;

public interface HttpServletRequest extends javax.servlet.ServletRequest {
    String getPathInfo();
    String getServletPath();
    String getHeader(String name);
}
''',
    "javax/servlet/http/HttpServletResponse.java": r'''package javax.servlet.http;

public interface HttpServletResponse extends javax.servlet.ServletResponse {
    int SC_OK = 200;
    int SC_NOT_FOUND = 404;
    int SC_SERVICE_UNAVAILABLE = 503;
    int SC_INTERNAL_SERVER_ERROR = 500;
    void setStatus(int sc);
}
''',
    "javax/servlet/http/HttpServlet.java": r'''package javax.servlet.http;

public abstract class HttpServlet implements javax.servlet.Servlet {
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException { }
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException { }
}
''',
    "org/killbill/billing/tenant/api/Tenant.java": r'''package org.killbill.billing.tenant.api;

public interface Tenant {
    java.util.UUID getId();
    String getExternalKey();
    String getApiKey();
}
''',
    "org/killbill/billing/osgi/api/Healthcheck.java": r'''package org.killbill.billing.osgi.api;

import java.util.Collections;
import java.util.Map;
import org.killbill.billing.tenant.api.Tenant;

public interface Healthcheck {

    HealthStatus getHealthStatus(Tenant tenant, Map properties);

    public class HealthStatus {
        private final boolean healthy;
        private final Map details;

        public HealthStatus(final boolean healthy, final Map details) {
            this.details = Map.copyOf(details);
            this.healthy = healthy;
        }

        public static HealthStatus healthy() { return new HealthStatus(true, Collections.EMPTY_MAP); }
        public static HealthStatus healthy(final String message) { return new HealthStatus(true, Collections.singletonMap("message", message)); }
        public static HealthStatus unHealthy(final String message) { return new HealthStatus(false, Collections.singletonMap("message", message)); }

        public boolean isHealthy() { return healthy; }
        public Map getDetails() { return Map.copyOf(details); }
    }
}
''',
    "org/killbill/billing/plugin/core/PluginServlet.java": r'''package org.killbill.billing.plugin.core;

import java.io.IOException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletResponse;
import org.killbill.billing.tenant.api.Tenant;

public abstract class PluginServlet extends HttpServlet {
    protected static final String APPLICATION_JSON = "application/json";

    protected Tenant getTenant(final ServletRequest req) { return (Tenant) req.getAttribute("killbill_tenant"); }
    protected void setJsonContentType(final ServletResponse resp) { }
    protected void buildOKResponse(final byte[] data, final HttpServletResponse resp) throws IOException { }
    protected void buildResponse(final int statusCode, final byte[] data, final HttpServletResponse resp) throws IOException { }
    protected void buildNotFoundResponse(final String message, final HttpServletResponse resp) throws IOException { }
}
''',
    "com/fasterxml/jackson/databind/ObjectMapper.java": r'''package com.fasterxml.jackson.databind;

public class ObjectMapper {
    public byte[] writeValueAsBytes(Object value) throws java.io.IOException { return new byte[0]; }
    public String writeValueAsString(Object value) throws java.io.IOException { return ""; }
}
''',

    'org/killbill/clock/Clock.java': r'''package org.killbill.clock;

public interface Clock {
    org.joda.time.DateTime getUTCNow(); org.joda.time.LocalDate getUTCToday();
}
''',
    'org/killbill/clock/DefaultClock.java': r'''package org.killbill.clock;

public class DefaultClock implements Clock {
    public org.joda.time.DateTime getUTCNow() { return null; }
    public org.joda.time.LocalDate getUTCToday() { return null; }
}
''',
    'org/killbill/billing/ObjectType.java': r'''package org.killbill.billing;

public enum ObjectType { ACCOUNT, INVOICE, INVOICE_ITEM }
''',
    'org/killbill/billing/catalog/api/Currency.java': r'''package org.killbill.billing.catalog.api;

public enum Currency { GBP, EUR, USD }
''',
    'org/killbill/billing/invoice/api/Invoice.java': r'''package org.killbill.billing.invoice.api;

import java.math.BigDecimal; import java.util.*;
import org.joda.time.LocalDate;
import org.killbill.billing.catalog.api.Currency; import org.killbill.billing.util.entity.Entity;
public interface Invoice extends Entity {
    boolean addInvoiceItem(InvoiceItem i); boolean addInvoiceItems(Collection<InvoiceItem> i);
    List<InvoiceItem> getInvoiceItems(); List<String> getTrackingIds(); boolean addTrackingIds(Collection<String> t);
    <T extends InvoiceItem> List<InvoiceItem> getInvoiceItems(Class<T> c); int getNumberOfItems();
    boolean addPayment(InvoicePayment p); boolean addPayments(Collection<InvoicePayment> p);
    List<InvoicePayment> getPayments(); int getNumberOfPayments(); UUID getAccountId(); Integer getInvoiceNumber();
    LocalDate getInvoiceDate(); LocalDate getTargetDate(); Currency getCurrency(); BigDecimal getPaidAmount();
    BigDecimal getOriginalChargedAmount(); BigDecimal getChargedAmount(); BigDecimal getCreditedAmount();
    BigDecimal getRefundedAmount(); BigDecimal getBalance(); boolean isMigrationInvoice(); InvoiceStatus getStatus();
    boolean isParentInvoice(); UUID getParentAccountId(); UUID getParentInvoiceId(); UUID getGroupId();
}
''',
    'org/killbill/billing/invoice/api/InvoiceApiException.java': r'''package org.killbill.billing.invoice.api;

public class InvoiceApiException extends Exception { }
''',
    'org/killbill/billing/invoice/api/InvoiceItem.java': r'''package org.killbill.billing.invoice.api;

import java.math.BigDecimal; import java.util.UUID;
import org.joda.time.LocalDate; import org.joda.time.DateTime;
import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.util.entity.Entity;
public interface InvoiceItem extends Entity {
    InvoiceItemType getInvoiceItemType(); UUID getInvoiceId(); UUID getAccountId(); UUID getChildAccountId();
    LocalDate getStartDate(); LocalDate getEndDate(); BigDecimal getAmount(); Currency getCurrency();
    String getDescription(); UUID getBundleId(); UUID getSubscriptionId(); String getProductName();
    String getPrettyProductName(); String getPlanName(); String getPrettyPlanName(); String getPhaseName();
    String getPrettyPhaseName(); String getUsageName(); String getPrettyUsageName(); BigDecimal getRate();
    UUID getLinkedItemId(); BigDecimal getQuantity(); String getItemDetails(); DateTime getCatalogEffectiveDate();
    boolean matches(Object other);
}
''',
    'org/killbill/billing/invoice/api/InvoiceItemType.java': r'''package org.killbill.billing.invoice.api;

public enum InvoiceItemType { EXTERNAL_CHARGE, FIXED, RECURRING, USAGE, TAX, ITEM_ADJ, REPAIR_ADJ, CBA_ADJ, CREDIT_ADJ, PARENT_SUMMARY }
''',
    'org/killbill/billing/invoice/api/InvoicePayment.java': r'''package org.killbill.billing.invoice.api;

public interface InvoicePayment { }
''',
    'org/killbill/billing/invoice/api/InvoiceStatus.java': r'''package org.killbill.billing.invoice.api;

public enum InvoiceStatus { DRAFT, COMMITTED, VOID }
''',
    'org/killbill/billing/invoice/api/formatters/InvoiceFormatter.java': r'''package org.killbill.billing.invoice.api.formatters;

import org.killbill.billing.invoice.api.Invoice;
public interface InvoiceFormatter extends Invoice {
    String getFormattedInvoiceDate(); String getFormattedChargedAmount(); String getFormattedPaidAmount();
    String getFormattedBalance(); org.killbill.billing.catalog.api.Currency getProcessedCurrency();
    String getProcessedPaymentRate();
}
''',
    'org/killbill/billing/invoice/api/formatters/InvoiceItemFormatter.java': r'''package org.killbill.billing.invoice.api.formatters;

import org.killbill.billing.invoice.api.InvoiceItem;
public interface InvoiceItemFormatter extends InvoiceItem {
    String getFormattedStartDate(); String getFormattedEndDate(); String getFormattedAmount();
}
''',
    'org/killbill/billing/invoice/plugin/api/AdditionalItemsResult.java': r'''package org.killbill.billing.invoice.plugin.api;

public interface AdditionalItemsResult extends InvoiceResult {
    java.util.List<org.killbill.billing.invoice.api.InvoiceItem> getAdditionalItems();
}
''',
    'org/killbill/billing/invoice/plugin/api/InvoiceContext.java': r'''package org.killbill.billing.invoice.plugin.api;

import org.killbill.billing.invoice.api.Invoice;
import org.killbill.billing.util.callcontext.CallContext;
public interface InvoiceContext extends CallContext {
    org.joda.time.LocalDate getTargetDate(); Invoice getInvoice();
    java.util.List<Invoice> getExistingInvoices(); boolean isDryRun(); boolean isRescheduled();
}
''',
    'org/killbill/billing/invoice/plugin/api/InvoiceFormatterFactory.java': r'''package org.killbill.billing.invoice.plugin.api;

import java.util.*;
import org.killbill.billing.currency.api.CurrencyConversionApi;
import org.killbill.billing.invoice.api.Invoice;
import org.killbill.billing.invoice.api.formatters.InvoiceFormatter;
public interface InvoiceFormatterFactory {
    InvoiceFormatter createInvoiceFormatter(String defaultLocale, String catalogBundlePath, Invoice invoice,
        Locale locale, CurrencyConversionApi api, ResourceBundle bundle, ResourceBundle defaultBundle);
}
''',
    'org/killbill/billing/invoice/plugin/api/InvoiceGroupingResult.java': r'''package org.killbill.billing.invoice.plugin.api;

public interface InvoiceGroupingResult extends InvoiceResult { }
''',
    'org/killbill/billing/invoice/plugin/api/InvoicePluginApi.java': r'''package org.killbill.billing.invoice.plugin.api;

import org.killbill.billing.invoice.api.Invoice;
import org.killbill.billing.payment.api.PluginProperty;
public interface InvoicePluginApi {
    PriorInvoiceResult priorCall(InvoiceContext c, Iterable<PluginProperty> p);
    AdditionalItemsResult getAdditionalInvoiceItems(Invoice i, boolean dryRun, Iterable<PluginProperty> p, InvoiceContext c);
    InvoiceGroupingResult getInvoiceGrouping(Invoice i, boolean dryRun, Iterable<PluginProperty> p, InvoiceContext c);
    OnSuccessInvoiceResult onSuccessCall(InvoiceContext c, Iterable<PluginProperty> p);
    OnFailureInvoiceResult onFailureCall(InvoiceContext c, Iterable<PluginProperty> p);
}
''',
    'org/killbill/billing/invoice/plugin/api/InvoiceResult.java': r'''package org.killbill.billing.invoice.plugin.api;

public interface InvoiceResult {
    Iterable<org.killbill.billing.payment.api.PluginProperty> getAdjustedPluginProperties();
}
''',
    'org/killbill/billing/invoice/plugin/api/OnFailureInvoiceResult.java': r'''package org.killbill.billing.invoice.plugin.api;

public interface OnFailureInvoiceResult extends InvoiceResult { }
''',
    'org/killbill/billing/invoice/plugin/api/OnSuccessInvoiceResult.java': r'''package org.killbill.billing.invoice.plugin.api;

public interface OnSuccessInvoiceResult extends InvoiceResult { }
''',
    'org/killbill/billing/invoice/plugin/api/PriorInvoiceResult.java': r'''package org.killbill.billing.invoice.plugin.api;

public interface PriorInvoiceResult extends InvoiceResult {
    boolean isAborted(); org.joda.time.DateTime getRescheduleDate();
}
''',
    'org/killbill/billing/invoice/template/formatters/DefaultInvoiceFormatter.java': r'''package org.killbill.billing.invoice.template.formatters;

import java.math.BigDecimal; import java.util.*;
import org.joda.time.*; import org.joda.time.format.DateTimeFormatter;
import org.killbill.billing.catalog.api.Currency; import org.killbill.billing.currency.api.CurrencyConversionApi;
import org.killbill.billing.invoice.api.*; import org.killbill.billing.invoice.api.formatters.InvoiceFormatter;
public class DefaultInvoiceFormatter implements InvoiceFormatter {
    public DefaultInvoiceFormatter(String defaultLocale, String catalogBundlePath, Invoice invoice, Locale locale,
        CurrencyConversionApi api, ResourceBundle bundle, ResourceBundle defaultBundle) { }
    protected List<InvoiceItem> mergeCBAAndCreditAdjustmentItems() { return null; }
    protected DateTimeFormatter getDateFormatter() { return null; }
    protected Locale getLocale() { return null; }
    protected Invoice getInvoice() { return null; }
    public List<InvoiceItem> getInvoiceItems() { return null; }
    public Integer getInvoiceNumber() { return null; }
    public List<String> getTrackingIds() { return null; }
    public boolean addTrackingIds(Collection<String> t) { return false; }
    public boolean addInvoiceItem(InvoiceItem i) { return false; }
    public boolean addInvoiceItems(Collection<InvoiceItem> i) { return false; }
    public <T extends InvoiceItem> List<InvoiceItem> getInvoiceItems(Class<T> c) { return null; }
    public int getNumberOfItems() { return 0; }
    public boolean addPayment(InvoicePayment p) { return false; }
    public boolean addPayments(Collection<InvoicePayment> p) { return false; }
    public List<InvoicePayment> getPayments() { return null; }
    public int getNumberOfPayments() { return 0; }
    public UUID getAccountId() { return null; }
    public BigDecimal getChargedAmount() { return null; }
    public BigDecimal getOriginalChargedAmount() { return null; }
    public BigDecimal getBalance() { return null; }
    public String getFormattedChargedAmount() { return null; }
    public String getFormattedPaidAmount() { return null; }
    public String getFormattedBalance() { return null; }
    public Currency getProcessedCurrency() { return null; }
    public String getProcessedPaymentRate() { return null; }
    public boolean isMigrationInvoice() { return false; }
    public LocalDate getInvoiceDate() { return null; }
    public LocalDate getTargetDate() { return null; }
    public Currency getCurrency() { return null; }
    public BigDecimal getPaidAmount() { return null; }
    public String getFormattedInvoiceDate() { return null; }
    public UUID getId() { return null; }
    public DateTime getCreatedDate() { return null; }
    public DateTime getUpdatedDate() { return null; }
    public InvoiceStatus getStatus() { return null; }
    public boolean isParentInvoice() { return false; }
    public UUID getParentAccountId() { return null; }
    public UUID getParentInvoiceId() { return null; }
    public UUID getGroupId() { return null; }
    public BigDecimal getCreditedAmount() { return null; }
    public BigDecimal getRefundedAmount() { return null; }
}
''',
    'org/killbill/billing/invoice/template/formatters/DefaultInvoiceItemFormatter.java': r'''package org.killbill.billing.invoice.template.formatters;

import java.math.BigDecimal; import java.util.*;
import org.joda.time.*; import org.joda.time.format.DateTimeFormatter;
import org.killbill.billing.catalog.api.Currency; import org.killbill.billing.invoice.api.*;
import org.killbill.billing.invoice.api.formatters.InvoiceItemFormatter;
public class DefaultInvoiceItemFormatter implements InvoiceItemFormatter {
    public DefaultInvoiceItemFormatter(String defaultLocale, String catalogBundlePath, InvoiceItem item,
        DateTimeFormatter dateFormatter, Locale locale, ResourceBundle bundle, ResourceBundle defaultBundle) { }
    public BigDecimal getAmount() { return null; } public Currency getCurrency() { return null; }
    public String getFormattedAmount() { return null; } public InvoiceItemType getInvoiceItemType() { return null; }
    public String getDescription() { return null; } public LocalDate getStartDate() { return null; }
    public LocalDate getEndDate() { return null; } public String getFormattedStartDate() { return null; }
    public String getFormattedEndDate() { return null; } public UUID getInvoiceId() { return null; }
    public UUID getAccountId() { return null; } public UUID getChildAccountId() { return null; }
    public UUID getBundleId() { return null; } public UUID getSubscriptionId() { return null; }
    public String getProductName() { return null; } public String getPrettyProductName() { return null; }
    public String getPlanName() { return null; } public String getPrettyPlanName() { return null; }
    public String getPhaseName() { return null; } public String getPrettyPhaseName() { return null; }
    public String getUsageName() { return null; } public String getPrettyUsageName() { return null; }
    public UUID getId() { return null; } public DateTime getCreatedDate() { return null; }
    public DateTime getUpdatedDate() { return null; } public BigDecimal getRate() { return null; }
    public UUID getLinkedItemId() { return null; } public BigDecimal getQuantity() { return null; }
    public String getItemDetails() { return null; } public DateTime getCatalogEffectiveDate() { return null; }
    public boolean matches(Object o) { throw new UnsupportedOperationException(); }
}
''',
    'org/killbill/billing/util/customfield/CustomField.java': r'''package org.killbill.billing.util.customfield;

import org.killbill.billing.util.entity.Entity;
public interface CustomField extends Entity {
    java.util.UUID getObjectId(); org.killbill.billing.ObjectType getObjectType();
    String getFieldName(); String getFieldValue();
}
''',
    'org/killbill/billing/util/callcontext/CallContext.java': r'''package org.killbill.billing.util.callcontext;

public interface CallContext extends TenantContext { }
''',
    'org/killbill/billing/util/callcontext/TenantContext.java': r'''package org.killbill.billing.util.callcontext;

public interface TenantContext {
    java.util.UUID getAccountId(); java.util.UUID getTenantId();
}
''',
    'org/killbill/billing/util/api/CustomFieldUserApi.java': r'''package org.killbill.billing.util.api;

import java.util.*;
import org.killbill.billing.ObjectType; import org.killbill.billing.util.callcontext.TenantContext;
import org.killbill.billing.util.customfield.CustomField;
public interface CustomFieldUserApi {
    List<CustomField> getCustomFieldsForObject(UUID objectId, ObjectType t, TenantContext c);
    List<CustomField> getCustomFieldsForAccountType(UUID accountId, ObjectType t, TenantContext c);
    List<CustomField> getCustomFieldsForAccount(UUID accountId, TenantContext c);
}
''',
    'org/killbill/billing/util/entity/Entity.java': r'''package org.killbill.billing.util.entity;

public interface Entity {
    java.util.UUID getId(); org.joda.time.DateTime getCreatedDate(); org.joda.time.DateTime getUpdatedDate();
}
''',
    'org/killbill/billing/currency/api/CurrencyConversionApi.java': r'''package org.killbill.billing.currency.api;

public interface CurrencyConversionApi { }
''',
    'org/killbill/billing/account/api/Account.java': r'''package org.killbill.billing.account.api;

import org.killbill.billing.util.entity.Entity;
public interface Account extends Entity {
    String getExternalKey(); String getName(); String getEmail(); String getAddress1(); String getAddress2();
    String getCompanyName(); String getCity(); String getStateOrProvince(); String getPostalCode();
    String getCountry(); String getPhone(); String getLocale(); String getNotes();
    org.killbill.billing.catalog.api.Currency getCurrency();
}
''',
    'org/killbill/billing/account/api/AccountApiException.java': r'''package org.killbill.billing.account.api;

public class AccountApiException extends Exception { }
''',
    'org/killbill/billing/account/api/AccountUserApi.java': r'''package org.killbill.billing.account.api;

import java.util.UUID;
import org.killbill.billing.util.callcontext.TenantContext;
public interface AccountUserApi {
    Account getAccountById(UUID id, TenantContext c) throws AccountApiException;
}
''',
    'org/killbill/billing/plugin/api/PluginApi.java': r'''package org.killbill.billing.plugin.api;

import java.util.UUID;
import org.killbill.billing.account.api.*;
import org.killbill.billing.osgi.libs.killbill.*;
import org.killbill.billing.util.callcontext.TenantContext;
import org.killbill.clock.Clock;
public abstract class PluginApi {
    protected final OSGIKillbillAPI killbillAPI;
    protected final OSGIConfigPropertiesService configProperties;
    protected final Clock clock;
    protected PluginApi(OSGIKillbillAPI a, OSGIConfigPropertiesService c, Clock k) {
        this.killbillAPI = a; this.configProperties = c; this.clock = k;
    }
    protected Account getAccount(UUID id, TenantContext c) throws org.killbill.billing.osgi.libs.killbill.OSGIServiceNotAvailable {
        return null;
    }
}
''',
    'org/killbill/billing/plugin/api/PluginProperties.java': r'''package org.killbill.billing.plugin.api;

import org.killbill.billing.payment.api.PluginProperty;
public class PluginProperties {
    public static String getValue(String n, String f, Iterable<PluginProperty> p) { return f; }
    public static String findPluginPropertyValue(String n, Iterable<PluginProperty> p) { return null; }
}
''',
    'org/killbill/billing/plugin/api/PluginTenantContext.java': r'''package org.killbill.billing.plugin.api;

import java.util.UUID;
import org.killbill.billing.util.callcontext.TenantContext;

public class PluginTenantContext implements TenantContext {
    private final UUID accountId; private final UUID tenantId;
    public PluginTenantContext(UUID accountId, UUID tenantId) { this.accountId = accountId; this.tenantId = tenantId; }
    public UUID getAccountId() { return accountId; }
    public UUID getTenantId() { return tenantId; }
}
''',
    'org/killbill/billing/plugin/api/invoice/PluginAdditionalItemsResult.java': r'''package org.killbill.billing.plugin.api.invoice;

import java.util.*;
import org.killbill.billing.invoice.api.InvoiceItem;
import org.killbill.billing.invoice.plugin.api.AdditionalItemsResult;
import org.killbill.billing.payment.api.PluginProperty;
public class PluginAdditionalItemsResult implements AdditionalItemsResult {
    public PluginAdditionalItemsResult() { }
    public PluginAdditionalItemsResult(List<InvoiceItem> items, Iterable<PluginProperty> p) { }
    public List<InvoiceItem> getAdditionalItems() { return null; }
    public Iterable<PluginProperty> getAdjustedPluginProperties() { return null; }
}
''',
    'org/killbill/billing/plugin/api/invoice/PluginInvoiceItem.java': r'''package org.killbill.billing.plugin.api.invoice;

import java.math.BigDecimal; import java.util.UUID;
import org.joda.time.*; import org.killbill.billing.catalog.api.Currency; import org.killbill.billing.invoice.api.*;
public class PluginInvoiceItem implements InvoiceItem {
    public PluginInvoiceItem(UUID id, InvoiceItemType t, UUID invoiceId, UUID accountId, UUID childAccountId,
        LocalDate start, LocalDate end, BigDecimal amount, Currency currency, String description, UUID subscriptionId,
        UUID bundleId, DateTime catalogEffectiveDate, String productName, String prettyProductName, String planName,
        String prettyPlanName, String phaseName, String prettyPhaseName, BigDecimal rate, UUID linkedItemId,
        String usageName, String prettyUsageName, BigDecimal quantity, String itemDetails, DateTime createdDate,
        DateTime updatedDate) { }
    public static PluginInvoiceItem createTaxItem(InvoiceItem m, UUID inv, BigDecimal a, String d) { return null; }
    public static PluginInvoiceItem createAdjustmentItem(InvoiceItem m, UUID inv, LocalDate s, LocalDate e, BigDecimal a, String d) { return null; }
    public InvoiceItemType getInvoiceItemType() { return null; } public UUID getInvoiceId() { return null; }
    public UUID getAccountId() { return null; } public UUID getChildAccountId() { return null; }
    public LocalDate getStartDate() { return null; } public LocalDate getEndDate() { return null; }
    public BigDecimal getAmount() { return null; } public Currency getCurrency() { return null; }
    public String getDescription() { return null; } public UUID getBundleId() { return null; }
    public UUID getSubscriptionId() { return null; } public String getProductName() { return null; }
    public String getPrettyProductName() { return null; } public String getPlanName() { return null; }
    public String getPrettyPlanName() { return null; } public String getPhaseName() { return null; }
    public String getPrettyPhaseName() { return null; } public String getUsageName() { return null; }
    public String getPrettyUsageName() { return null; } public BigDecimal getRate() { return null; }
    public UUID getLinkedItemId() { return null; } public BigDecimal getQuantity() { return null; }
    public String getItemDetails() { return null; } public DateTime getCatalogEffectiveDate() { return null; }
    public boolean matches(Object o) { return false; } public UUID getId() { return null; }
    public DateTime getCreatedDate() { return null; } public DateTime getUpdatedDate() { return null; }
}
''',
    'org/killbill/billing/plugin/api/invoice/PluginInvoicePluginApi.java': r'''package org.killbill.billing.plugin.api.invoice;

import org.killbill.billing.invoice.api.Invoice;
import org.killbill.billing.invoice.plugin.api.*;
import org.killbill.billing.osgi.libs.killbill.*;
import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.plugin.api.PluginApi;
import org.killbill.clock.Clock;
public class PluginInvoicePluginApi extends PluginApi implements InvoicePluginApi {
    public PluginInvoicePluginApi(OSGIKillbillAPI a, OSGIConfigPropertiesService c, Clock k) { super(a,c,k); }
    public PriorInvoiceResult priorCall(InvoiceContext c, Iterable<PluginProperty> p) { return null; }
    public AdditionalItemsResult getAdditionalInvoiceItems(Invoice i, boolean d, Iterable<PluginProperty> p, InvoiceContext c) { return null; }
    public InvoiceGroupingResult getInvoiceGrouping(Invoice i, boolean d, Iterable<PluginProperty> p, InvoiceContext c) { return null; }
    public OnSuccessInvoiceResult onSuccessCall(InvoiceContext c, Iterable<PluginProperty> p) { return null; }
    public OnFailureInvoiceResult onFailureCall(InvoiceContext c, Iterable<PluginProperty> p) { return null; }
}
''',
    'org/killbill/billing/plugin/api/invoice/PluginTaxCalculator.java': r'''package org.killbill.billing.plugin.api.invoice;

import java.math.BigDecimal; import java.util.*;
import org.killbill.billing.invoice.api.*;
import org.killbill.billing.osgi.libs.killbill.OSGIKillbillAPI;
import org.killbill.billing.util.callcontext.TenantContext;
public abstract class PluginTaxCalculator {
    public static final List<InvoiceItemType> TAXABLE_ITEM_TYPES = null;
    public static final List<InvoiceItemType> ADJUSTMENT_ITEM_TYPES = null;
    protected final OSGIKillbillAPI osgiKillbillAPI;
    public PluginTaxCalculator(OSGIKillbillAPI a) { this.osgiKillbillAPI = a; }
    public List<NewItemToTax> computeTaxItems(Invoice i, Map<UUID, Set<UUID>> already, TenantContext c) throws InvoiceApiException { return null; }
    protected InvoiceItem buildTaxItem(InvoiceItem o, UUID invoiceId, InvoiceItem adj, BigDecimal amt, String desc) { return null; }
    protected boolean isTaxableItem(InvoiceItem i) { return false; }
    protected boolean isTaxItem(InvoiceItem i) { return false; }
    protected boolean isAdjustmentItem(InvoiceItem i) { return false; }
    protected BigDecimal netAmount(InvoiceItem i, Iterable<InvoiceItem> adj) { return null; }
    protected BigDecimal sum(Iterable<InvoiceItem> items) { return null; }
    public static class NewItemToTax {
        public NewItemToTax(Invoice i, InvoiceItem t, List<InvoiceItem> a, boolean r) { }
        public Invoice getInvoice() { return null; }
        public InvoiceItem getTaxableItem() { return null; }
        public List<InvoiceItem> getAdjustmentItems() { return null; }
        public boolean isReturnOnly() { return false; }
    }
}
''',
    'org/killbill/billing/plugin/api/notification/PluginConfigurationEventHandler.java': r'''package org.killbill.billing.plugin.api.notification;

import org.killbill.billing.osgi.libs.killbill.OSGIKillbillEventDispatcher;
public class PluginConfigurationEventHandler implements OSGIKillbillEventDispatcher.OSGIKillbillEventHandler {
    public PluginConfigurationEventHandler(PluginConfigurationHandler... h) { }
}
''',
    'org/killbill/billing/plugin/api/notification/PluginConfigurationHandler.java': r'''package org.killbill.billing.plugin.api.notification;

import java.util.*;
import org.killbill.billing.osgi.libs.killbill.OSGIKillbillAPI;
public abstract class PluginConfigurationHandler {
    public PluginConfigurationHandler(String pluginName, OSGIKillbillAPI a) { }
    protected abstract void configure(UUID kbTenantId);
    protected Properties getTenantConfigurationAsProperties(UUID t) { return null; }
}
''',
    'org/killbill/billing/plugin/api/notification/PluginTenantConfigurableConfigurationHandler.java': r'''package org.killbill.billing.plugin.api.notification;

import java.util.*;
import org.killbill.billing.osgi.libs.killbill.OSGIKillbillAPI;
public abstract class PluginTenantConfigurableConfigurationHandler<C> extends PluginConfigurationHandler {
    public PluginTenantConfigurableConfigurationHandler(String pluginName, OSGIKillbillAPI a) { super(pluginName, a); }
    protected abstract C createConfigurable(Properties properties);
    protected void configure(UUID kbTenantId) { }
    public void setDefaultConfigurable(C c) { }
    public C getConfigurable(UUID kbTenantId) { return null; }
}
''',
    'org/killbill/billing/payment/api/PluginProperty.java': r'''package org.killbill.billing.payment.api;

public class PluginProperty {
    public PluginProperty(String k, Object v, boolean u) { }
    public String getKey() { return null; } public Object getValue() { return null; }
}
''',
    'org/killbill/billing/osgi/libs/killbill/KillbillActivatorBase.java': r'''package org.killbill.billing.osgi.libs.killbill;

import org.killbill.billing.osgi.api.OSGIKillbillRegistrar;
import org.osgi.framework.BundleContext;
public abstract class KillbillActivatorBase {
    protected OSGIKillbillAPI killbillAPI;
    protected OSGIKillbillRegistrar registrar;
    protected OSGIKillbillClock clock;
    protected OSGIKillbillEventDispatcher dispatcher;
    protected OSGIConfigPropertiesService configProperties;
    public void start(BundleContext c) throws Exception { }
    public void stop(BundleContext c) throws Exception { }
}
''',
    'org/killbill/billing/osgi/libs/killbill/OSGIConfigPropertiesService.java': r'''package org.killbill.billing.osgi.libs.killbill;

public class OSGIConfigPropertiesService {
    public String getString(String n) { return null; }
    public java.util.Properties getProperties() { return null; }
}
''',
    'org/killbill/billing/osgi/libs/killbill/OSGIKillbillAPI.java': r'''package org.killbill.billing.osgi.libs.killbill;

import org.killbill.billing.account.api.AccountUserApi;
import org.killbill.billing.util.api.CustomFieldUserApi;
public class OSGIKillbillAPI {
    public CustomFieldUserApi getCustomFieldUserApi() { return null; }
    public AccountUserApi getAccountUserApi() { return null; }
}
''',
    'org/killbill/billing/osgi/libs/killbill/OSGIKillbillClock.java': r'''package org.killbill.billing.osgi.libs.killbill;

public class OSGIKillbillClock { }
''',
    'org/killbill/billing/osgi/libs/killbill/OSGIKillbillEventDispatcher.java': r'''package org.killbill.billing.osgi.libs.killbill;

public class OSGIKillbillEventDispatcher {
    public interface OSGIHandlerMarker { }
    public interface OSGIKillbillEventHandler extends OSGIHandlerMarker { }
    public void registerEventHandlers(OSGIHandlerMarker... h) { }
    public void unregisterAllHandlers() { }
}
''',
    'org/killbill/billing/osgi/libs/killbill/OSGIServiceNotAvailable.java': r'''package org.killbill.billing.osgi.libs.killbill;

public class OSGIServiceNotAvailable extends RuntimeException { }
''',
    'org/killbill/billing/osgi/api/OSGIKillbillRegistrar.java': r'''package org.killbill.billing.osgi.api;

import org.osgi.framework.BundleContext;
public class OSGIKillbillRegistrar {
    public <S> void registerService(BundleContext c, Class<S> k, S s, java.util.Dictionary<String,?> p) { }
    public <S> void unregisterService(Class<S> k) { } public void unregisterAll() { }
}
''',
    'org/killbill/billing/osgi/api/OSGIPluginProperties.java': r'''package org.killbill.billing.osgi.api;

public class OSGIPluginProperties { public static final String PLUGIN_NAME_PROP = "killbill.pluginName"; }
''',
    'org/osgi/framework/BundleContext.java': r'''package org.osgi.framework;

public interface BundleContext { }
''',
    'org/slf4j/Logger.java': r'''package org.slf4j;

/** No-op so stubbed code can actually run under the offline harness. */
public class Logger {
    public void trace(String m, Object... a) { }
    public void debug(String m, Object... a) { }
    public void info(String m, Object... a) { }
    public void warn(String m, Object... a) { }
    public void error(String m, Object... a) { }
}
''',
    'org/slf4j/LoggerFactory.java': r'''package org.slf4j;

public class LoggerFactory {
    public static Logger getLogger(Class<?> c) { return new Logger(); }
}
''',
    'org/joda/time/DateTime.java': r'''package org.joda.time;

public class DateTime { }
''',
    'org/joda/time/DateTimeZone.java': r'''package org.joda.time;

public class DateTimeZone { }
''',
    'org/joda/time/LocalDate.java': r'''package org.joda.time;

public class LocalDate {
    public int getYear() { return 0; } public int getMonthOfYear() { return 1; }
    public int getDayOfMonth() { return 1; } public LocalDate minusDays(int d) { return this; }
    public boolean isBefore(LocalDate o) { return false; }
    public String toString(org.joda.time.format.DateTimeFormatter f) { return ""; }
}
''',
    'org/joda/time/format/DateTimeFormat.java': r'''package org.joda.time.format;

public class DateTimeFormat {
    public static DateTimeFormatter mediumDate() { return new DateTimeFormatter(); }
}
''',
    'org/joda/time/format/DateTimeFormatter.java': r'''package org.joda.time.format;

public class DateTimeFormatter {
    public DateTimeFormatter withLocale(java.util.Locale l) { return this; }
}
''',
}

for rel, content in STUBS.items():
    target = os.path.join(ROOT, *rel.split('/'))
    os.makedirs(os.path.dirname(target), exist_ok=True)
    with open(target, 'w') as fh:
        fh.write(content)

print('generated %d stub files into %s' % (len(STUBS), ROOT))
