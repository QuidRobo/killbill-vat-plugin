import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Properties;

import com.quidrobo.killbill.vat.calc.VatComputation;
import com.quidrobo.killbill.vat.core.PriceMode;
import com.quidrobo.killbill.vat.core.VatConfig;
import com.quidrobo.killbill.vat.rates.VatRateTable;
import com.quidrobo.killbill.vat.resolve.DigitalServicesVatResolver;
import com.quidrobo.killbill.vat.resolve.VatTreatment;
import com.quidrobo.killbill.vat.resolve.VatTreatmentKind;
import com.quidrobo.killbill.vat.resolve.VatTreatmentRequest;
import com.quidrobo.killbill.vat.vies.VatNumberFormat;

/** Plain-Java harness so the pure logic can be exercised without Maven or the Kill Bill jars. */
public final class VatCoreTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(final String[] args) {
        priceModes();
        rateTable();
        resolver();
        configProblems();
        vatNumbers();

        System.out.println();
        System.out.println(failed == 0
                           ? "ALL " + passed + " ASSERTIONS PASSED"
                           : passed + " passed, " + failed + " FAILED");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ price modes

    private static void priceModes() {
        section("Price modes");

        VatComputation.VatSplit s = split("120.00", "0.20", PriceMode.EXCLUSIVE);
        eq("exclusive 120.00 @20% net", "120.00", s.getNet());
        eq("exclusive 120.00 @20% vat", "24.00", s.getVat());
        eq("exclusive gross", "144.00", s.getGross());
        is("exclusive needs no rewrite", !s.requiresRewrite(new BigDecimal("120.00")));

        s = split("120.00", "0.20", PriceMode.INCLUSIVE);
        eq("inclusive 120.00 @20% net", "100.00", s.getNet());
        eq("inclusive 120.00 @20% vat", "20.00", s.getVat());
        eq("inclusive total is unchanged", "120.00", s.getGross());
        is("inclusive needs a rewrite", s.requiresRewrite(new BigDecimal("120.00")));

        // The rounding case that breaks naive implementations: 9.99 / 1.2 = 8.325
        s = split("9.99", "0.20", PriceMode.INCLUSIVE);
        eq("inclusive 9.99 @20% net", "8.33", s.getNet());
        eq("inclusive 9.99 @20% vat", "1.66", s.getVat());
        eq("inclusive 9.99 net+vat still equals gross", "9.99", s.getGross());

        // Computing vat independently as gross*rate/(1+rate) would give 1.665 -> 1.67, and
        // 8.33 + 1.67 = 10.00, a penny more than the customer was quoted.
        is("inclusive never drifts from the quoted price",
           s.getNet().add(s.getVat()).compareTo(new BigDecimal("9.99")) == 0);

        s = split("33.33", "0.20", PriceMode.EXCLUSIVE);
        eq("exclusive 33.33 @20% vat rounds half up", "6.67", s.getVat());

        s = split("120.00", "0", PriceMode.INCLUSIVE);
        eq("inclusive at 0% leaves the charge alone", "120.00", s.getNet());
        eq("inclusive at 0% charges no vat", "0.00", s.getVat());
        is("inclusive at 0% needs no rewrite", !s.requiresRewrite(new BigDecimal("120.00")));

        s = split("120.00", "0.19", PriceMode.INCLUSIVE);
        eq("inclusive 120.00 @19% net", "100.84", s.getNet());
        eq("inclusive 120.00 @19% vat", "19.16", s.getVat());
        eq("inclusive 19% total unchanged", "120.00", s.getGross());

        is("null amount is safe", split(null, "0.20", PriceMode.INCLUSIVE).getVat()
                                  .compareTo(BigDecimal.ZERO) == 0);

        eq("parse INCLUSIVE", "INCLUSIVE", PriceMode.parse("inclusive", PriceMode.EXCLUSIVE).name());
        eq("parse gross as INCLUSIVE", "INCLUSIVE", PriceMode.parse("GROSS", PriceMode.EXCLUSIVE).name());
        eq("parse junk falls back", "EXCLUSIVE", PriceMode.parse("banana", PriceMode.EXCLUSIVE).name());
    }

    // ------------------------------------------------------------------ rate table

    private static void rateTable() {
        section("Rate table");

        final Properties p = new Properties();
        p.setProperty("org.killbill.billing.plugin.vat.rates.GB.standard.0.rate", "0.175");
        p.setProperty("org.killbill.billing.plugin.vat.rates.GB.standard.0.from", "2008-12-01");
        p.setProperty("org.killbill.billing.plugin.vat.rates.GB.standard.0.to", "2010-01-01");
        p.setProperty("org.killbill.billing.plugin.vat.rates.GB.standard.1.rate", "0.20");
        p.setProperty("org.killbill.billing.plugin.vat.rates.GB.standard.1.from", "2011-01-04");
        p.setProperty("org.killbill.billing.plugin.vat.rates.DE.standard", "19%");
        p.setProperty("org.killbill.billing.plugin.vat.rates.IE.reduced", "0.09");

        final VatRateTable table = VatRateTable.fromProperties(p, "org.killbill.billing.plugin.vat.");

        eq("GB rate in 2009", "0.175",
           table.findRate("GB", "standard", LocalDate.of(2009, 6, 1)).getRate());
        eq("GB rate today", "0.20",
           table.findRate("GB", "standard", LocalDate.of(2026, 9, 6)).getRate());
        is("GB rate in the 2010 gap is absent",
           table.findRate("GB", "standard", LocalDate.of(2010, 6, 1)) == null);
        eq("percent shorthand parses", "0.19",
           table.findRate("DE", "standard", LocalDate.of(2026, 9, 6)).getRate());
        eq("percent label", "20%",
           table.findRate("GB", "standard", LocalDate.of(2026, 9, 6)).toPercentLabel());
        eq("fractional percent label", "9%",
           table.findRate("IE", "reduced", LocalDate.of(2026, 9, 6)).toPercentLabel());
        is("unknown jurisdiction", table.findRate("US", "standard", LocalDate.of(2026, 9, 6)) == null);
        is("unknown kind", table.findRate("DE", "reduced", LocalDate.of(2026, 9, 6)) == null);
        is("hasJurisdiction is case insensitive", table.hasJurisdiction("gb"));

        // A bare "20" is ambiguous between 20% and 2000%, so it is rejected rather than guessed.
        is("bare 20 is rejected", VatRateTable.parseRate("20") == null);
        eq("20% parses", "0.20", VatRateTable.parseRate("20%"));
        eq("0.2 parses", "0.2", VatRateTable.parseRate("0.2"));
        is("junk is rejected", VatRateTable.parseRate("abc") == null);
    }

    // ------------------------------------------------------------------ resolver

    private static void resolver() {
        section("Treatment resolver");

        final Properties p = new Properties();
        p.setProperty("org.killbill.billing.plugin.vat.supplierCountry", "GB");
        p.setProperty("org.killbill.billing.plugin.vat.rates.GB.standard", "0.20");
        p.setProperty("org.killbill.billing.plugin.vat.rates.DE.standard", "0.19");
        p.setProperty("org.killbill.billing.plugin.vat.rates.NO.standard", "0.25");
        p.setProperty("org.killbill.billing.plugin.vat.registeredCountries", "NO");
        p.setProperty("org.killbill.billing.plugin.vat.vatNumberValidation", "CHECKSUM");
        p.setProperty("org.killbill.billing.plugin.vat.ossRegistered", "true");

        final VatConfig config = new VatConfig(p);
        final DigitalServicesVatResolver resolver = new DigitalServicesVatResolver();
        resolver.init(config, config.getRateTable());

        VatTreatment t = resolver.resolve(req("GB", null, false));
        eq("UK customer is domestic", "DOMESTIC", t.getKind().name());
        eq("UK customer pays 20%", "0.20", t.getRate());
        eq("UK tax item description", "VAT 20%", t.getDescription());

        t = resolver.resolve(req("GB", "GB484549056", true));
        eq("UK business still pays UK VAT", "DOMESTIC", t.getKind().name());

        t = resolver.resolve(req("DE", "DE811234567", true));
        eq("validated EU business is reverse charge", "REVERSE_CHARGE", t.getKind().name());
        eq("reverse charge is zero rated", "0", t.getRate());
        is("reverse charge carries the legend", t.getLegend() != null && t.getLegend().contains("Reverse charge"));
        is("reverse charge carries the customer number", "DE811234567".equals(t.getCustomerVatNumber()));

        // The asymmetry that protects the supplier.
        t = resolver.resolve(req("DE", "DE811234567", false));
        eq("UNVALIDATED EU vat number is NOT a reverse charge", "DESTINATION", t.getKind().name());
        eq("unvalidated EU falls back to the German rate", "0.19", t.getRate());

        t = resolver.resolve(req("DE", null, false));
        eq("EU consumer pays their own rate", "DESTINATION", t.getKind().name());
        eq("EU consumer German rate", "0.19", t.getRate());


        // Holding a DE rate is not authority to charge German VAT.
        final java.util.Properties noOss = new java.util.Properties();
        noOss.setProperty("org.killbill.billing.plugin.vat.supplierCountry", "GB");
        noOss.setProperty("org.killbill.billing.plugin.vat.rates.GB.standard", "0.20");
        noOss.setProperty("org.killbill.billing.plugin.vat.rates.DE.standard", "0.19");
        final VatConfig noOssConfig = new VatConfig(noOss);
        final DigitalServicesVatResolver noOssResolver = new DigitalServicesVatResolver();
        noOssResolver.init(noOssConfig, noOssConfig.getRateTable());
        is("ossRegistered defaults to false", !noOssConfig.isOssRegistered());
        t = noOssResolver.resolve(req("DE", null, false));
        eq("EU B2C without an OSS registration is not charged German VAT", "DOMESTIC", t.getKind().name());
        eq("it falls back to the supplier rate", "0.20", t.getRate());
        is("and it says why", t.getReason().contains("OSS"));

        final java.util.Properties oss = new java.util.Properties();
        oss.setProperty("org.killbill.billing.plugin.vat.supplierCountry", "GB");
        oss.setProperty("org.killbill.billing.plugin.vat.rates.GB.standard", "0.20");
        oss.setProperty("org.killbill.billing.plugin.vat.rates.DE.standard", "0.19");
        oss.setProperty("org.killbill.billing.plugin.vat.ossRegistered", "true");
        final VatConfig ossConfig = new VatConfig(oss);
        final DigitalServicesVatResolver ossResolver = new DigitalServicesVatResolver();
        ossResolver.init(ossConfig, ossConfig.getRateTable());
        t = ossResolver.resolve(req("DE", null, false));
        eq("with OSS registered the destination rate applies", "DESTINATION", t.getKind().name());
        eq("German rate under OSS", "0.19", t.getRate());
        eq("reverse charge is unaffected by the OSS flag", "REVERSE_CHARGE",
           ossResolver.resolve(req("DE", "DE811234567", true)).getKind().name());

        t = resolver.resolve(req("US", null, false));
        eq("US customer is outside scope", "OUTSIDE_SCOPE", t.getKind().name());
        is("outside scope explains itself", t.getReason().contains("US"));

        t = resolver.resolve(req("US", "US123456", true));
        eq("non-EU with a number is still outside scope without a registration",
           "OUTSIDE_SCOPE", t.getKind().name());


        t = resolver.resolve(req("NO", "NO999999999MVA", true));
        eq("registered non-EU business still gets the local rate", "DESTINATION", t.getKind().name());

        final java.util.Properties eu = new java.util.Properties();
        eu.setProperty("org.killbill.billing.plugin.vat.supplierCountry", "IE");
        eu.setProperty("org.killbill.billing.plugin.vat.rates.IE.standard", "0.23");
        eu.setProperty("org.killbill.billing.plugin.vat.reverseChargeCountries", "GB,DE,FR");
        final VatConfig euConfig = new VatConfig(eu);
        final DigitalServicesVatResolver euResolver = new DigitalServicesVatResolver();
        euResolver.init(euConfig, euConfig.getRateTable());
        eq("an EU supplier can add GB to the reverse charge area", "REVERSE_CHARGE",
           euResolver.resolve(req("GB", "GB484549056", true)).getKind().name());

        t = resolver.resolve(req("NO", null, false));
        eq("registered non-EU country is charged", "DESTINATION", t.getKind().name());
        eq("Norwegian rate", "0.25", t.getRate());

        t = resolver.resolve(req(null, null, false));
        eq("unknown country defaults to domestic", "DOMESTIC", t.getKind().name());
        is("unknown country says why", t.getReason().contains("no country"));

        // A configuration gap must not silently become 0%.
        final Properties gap = new Properties();
        gap.setProperty("org.killbill.billing.plugin.vat.supplierCountry", "GB");
        final VatConfig gapConfig = new VatConfig(gap);
        final DigitalServicesVatResolver gapResolver = new DigitalServicesVatResolver();
        gapResolver.init(gapConfig, gapConfig.getRateTable());
        t = gapResolver.resolve(req("GB", null, false));
        eq("a missing rate is not silently zero rated", "OUTSIDE_SCOPE", t.getKind().name());
        is("a missing rate names the gap", t.getReason().contains("no standard rate configured"));

        is("chargesVat is true for domestic", resolver.resolve(req("GB", null, false)).chargesVat());
        is("chargesVat is false for reverse charge",
           !resolver.resolve(req("DE", "DE811234567", true)).chargesVat());

        // unknownCountryTreatment is configurable for anyone who prefers the other risk.
        final Properties strict = new Properties();
        strict.setProperty("org.killbill.billing.plugin.vat.supplierCountry", "GB");
        strict.setProperty("org.killbill.billing.plugin.vat.rates.GB.standard", "0.20");
        strict.setProperty("org.killbill.billing.plugin.vat.unknownCountryTreatment", "OUTSIDE_SCOPE");
        final VatConfig strictConfig = new VatConfig(strict);
        final DigitalServicesVatResolver strictResolver = new DigitalServicesVatResolver();
        strictResolver.init(strictConfig, strictConfig.getRateTable());
        eq("unknownCountryTreatment is honoured", "OUTSIDE_SCOPE",
           strictResolver.resolve(req(null, null, false)).getKind().name());
    }

    // ------------------------------------------------------------------ config problems

    private static void configProblems() {
        section("Configuration diagnostics");

        // Silence is the enemy: a typo must be reported, not ignored.
        final Properties typo = new Properties();
        typo.setProperty("org.killbill.billing.plugin.vat.supplierCountry", "GB");
        typo.setProperty("org.killbill.billing.plugin.vat.rates.GB.standrd", "0.20");
        typo.setProperty("org.killbill.billing.plugin.vat.roundng.scale", "2");
        final VatConfig typoConfig = new VatConfig(typo);
        is("an unrecognised property is reported",
           joined(typoConfig.getProblems()).contains("roundng.scale"));
        is("a misspelt rate kind leaves the supplier country untaxed, and is reported",
           joined(typoConfig.getProblems()).contains("No standard rate is in force today for the supplier country GB"));

        final Properties empty = new Properties();
        is("no rates at all is reported",
           joined(new VatConfig(empty).getProblems()).contains("No VAT rates are configured"));

        final Properties badRate = new Properties();
        badRate.setProperty("org.killbill.billing.plugin.vat.rates.GB.standard", "20");
        is("an ambiguous bare rate is reported",
           joined(new VatConfig(badRate).getProblems()).contains("could not be read"));

        final Properties euNoOss = new Properties();
        euNoOss.setProperty("org.killbill.billing.plugin.vat.supplierCountry", "GB");
        euNoOss.setProperty("org.killbill.billing.plugin.vat.rates.GB.standard", "0.20");
        euNoOss.setProperty("org.killbill.billing.plugin.vat.rates.DE.standard", "0.19");
        is("EU rates without an OSS registration are reported",
           joined(new VatConfig(euNoOss).getProblems()).contains("ossRegistered is false"));

        final Properties good = new Properties();
        good.setProperty("org.killbill.billing.plugin.vat.supplierCountry", "GB");
        good.setProperty("org.killbill.billing.plugin.vat.rates.GB.standard", "0.20");
        good.setProperty("org.killbill.billing.plugin.vat.vatNumberValidation", "EXTERNAL");
        final VatConfig goodConfig = new VatConfig(good);
        is("a sound UK-only configuration reports nothing",
           goodConfig.getProblems().isEmpty());

        // CHECKSUM is usable but is not proof of registration, so it is surfaced.
        final Properties checksum = new Properties();
        checksum.setProperty("org.killbill.billing.plugin.vat.supplierCountry", "GB");
        checksum.setProperty("org.killbill.billing.plugin.vat.rates.GB.standard", "0.20");
        is("CHECKSUM validation is flagged as not production grade",
           joined(new VatConfig(checksum).getProblems()).contains("not that the registration exists"));

        // Legitimate free-form keys must not be mistaken for typos.
        final Properties freeform = new Properties();
        freeform.setProperty("org.killbill.billing.plugin.vat.supplierCountry", "GB");
        freeform.setProperty("org.killbill.billing.plugin.vat.rates.GB.standard", "0.20");
        freeform.setProperty("org.killbill.billing.plugin.vat.vatNumberValidation", "EXTERNAL");
        freeform.setProperty("org.killbill.billing.plugin.vat.priceMode.plans.growth-monthly", "INCLUSIVE");
        freeform.setProperty("org.killbill.billing.plugin.vat.priceMode.products.Growth", "EXCLUSIVE");
        is("plan and product overrides are not flagged as typos",
           new VatConfig(freeform).getProblems().isEmpty());
    }

    private static String joined(final java.util.List<String> problems) {
        return String.join(" | ", problems);
    }

    // ------------------------------------------------------------------ vat numbers

    private static void vatNumbers() {
        section("VAT number format");

        is("QuidRobo's own number validates", VatNumberFormat.isPlausible("GB 484549056"));
        is("spaces and dashes are stripped", VatNumberFormat.isPlausible("GB-484-549-056"));
        is("a corrupted check digit fails", !VatNumberFormat.isPlausible("GB484549057"));
        is("a truncated number fails", !VatNumberFormat.isPlausible("GB48454905"));
        is("an unknown prefix fails", !VatNumberFormat.isPlausible("ZZ123456789"));
        is("null is not plausible", !VatNumberFormat.isPlausible(null));
        is("empty is not plausible", !VatNumberFormat.isPlausible("   "));
        is("GB government departments pass", VatNumberFormat.isPlausible("GBGD001"));
        is("Northern Ireland uses the GB rules", VatNumberFormat.isPlausible("XI484549056"));
        eq("country prefix is read", "DE", VatNumberFormat.countryOf(VatNumberFormat.normalise("DE 811 234 567")));
        eq("normalisation", "GB484549056", VatNumberFormat.normalise("gb 484-549.056"));
        is("a structurally wrong NL number fails", !VatNumberFormat.isPlausible("NL123456789"));
        is("an unimplemented country passes on structure alone", VatNumberFormat.isPlausible("PL1234567890"));
        is("a structurally wrong PL number still fails", !VatNumberFormat.isPlausible("PL12345"));
    }

    // ------------------------------------------------------------------ helpers

    private static VatComputation.VatSplit split(final String amount, final String rate, final PriceMode mode) {
        return VatComputation.split(amount == null ? null : new BigDecimal(amount),
                                    new BigDecimal(rate), mode, 2, RoundingMode.HALF_UP);
    }

    private static VatTreatmentRequest req(final String country, final String vatNumber, final boolean validated) {
        return VatTreatmentRequest.builder()
                                  .customerCountry(country)
                                  .customerVatNumber(vatNumber)
                                  .customerVatNumberValidated(validated)
                                  .taxPoint(LocalDate.of(2026, 9, 1))
                                  .planName("growth-monthly")
                                  .productName("Growth")
                                  .build();
    }

    private static void section(final String name) {
        System.out.println();
        System.out.println("== " + name + " ==");
    }

    private static void eq(final String what, final String expected, final Object actual) {
        final String actualStr = actual == null ? "null"
                                 : actual instanceof BigDecimal ? ((BigDecimal) actual).toPlainString()
                                 : actual.toString();
        if (expected.equals(actualStr)) {
            pass(what);
        } else {
            fail(what + "  expected <" + expected + "> got <" + actualStr + ">");
        }
    }

    private static void is(final String what, final boolean condition) {
        if (condition) {
            pass(what);
        } else {
            fail(what);
        }
    }

    private static void pass(final String what) {
        passed++;
        System.out.println("  ok   " + what);
    }

    private static void fail(final String what) {
        failed++;
        System.out.println("  FAIL " + what);
    }

    private static void eq(final String what, final String expected, final BigDecimal actual) {
        eq(what, expected, (Object) actual);
    }
}
