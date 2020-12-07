package org.folio.rest.impl;

import static java.lang.String.format;
import static org.folio.HttpStatus.HTTP_BAD_REQUEST;
import static org.folio.HttpStatus.HTTP_INTERNAL_SERVER_ERROR;
import static org.folio.rest.utils.ResourceClients.buildRefundReportClient;
import static org.folio.test.support.EntityBuilder.createCampus;
import static org.folio.test.support.EntityBuilder.createHoldingsRecord;
import static org.folio.test.support.EntityBuilder.createInstance;
import static org.folio.test.support.EntityBuilder.createInstitution;
import static org.folio.test.support.EntityBuilder.createItem;
import static org.folio.test.support.EntityBuilder.createLibrary;
import static org.folio.test.support.EntityBuilder.createLocaleSettingsConfigurations;
import static org.folio.test.support.EntityBuilder.createLocation;
import static org.folio.test.support.matcher.RefundReportEntryMatcher.refundReportEntryMatcher;
import static org.folio.test.support.matcher.constant.DbTable.ACCOUNTS_TABLE;
import static org.folio.test.support.matcher.constant.DbTable.FEEFINES_TABLE;
import static org.folio.test.support.matcher.constant.DbTable.FEE_FINE_ACTIONS_TABLE;
import static org.folio.test.support.matcher.constant.ServicePath.ACCOUNTS_PATH;
import static org.folio.test.support.matcher.constant.ServicePath.HOLDINGS_PATH;
import static org.folio.test.support.matcher.constant.ServicePath.INSTANCES_PATH;
import static org.folio.test.support.matcher.constant.ServicePath.USERS_GROUPS_PATH;
import static org.folio.test.support.matcher.constant.ServicePath.USERS_PATH;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.collection.IsIterableWithSize.iterableWithSize;

import java.util.Date;
import java.util.List;
import java.util.stream.IntStream;

import org.apache.http.HttpStatus;
import org.folio.rest.domain.MonetaryValue;
import org.folio.rest.jaxrs.model.Account;
import org.folio.rest.jaxrs.model.Campus;
import org.folio.rest.jaxrs.model.Feefineaction;
import org.folio.rest.jaxrs.model.HoldingsRecord;
import org.folio.rest.jaxrs.model.Instance;
import org.folio.rest.jaxrs.model.Institution;
import org.folio.rest.jaxrs.model.Item;
import org.folio.rest.jaxrs.model.KvConfigurations;
import org.folio.rest.jaxrs.model.Library;
import org.folio.rest.jaxrs.model.Location;
import org.folio.rest.jaxrs.model.RefundReportEntry;
import org.folio.rest.jaxrs.model.User;
import org.folio.rest.jaxrs.model.UserGroup;
import org.folio.rest.utils.ResourceClient;
import org.folio.test.support.ApiTests;
import org.folio.test.support.EntityBuilder;
import org.folio.test.support.matcher.constant.ServicePath;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.junit.Before;
import org.junit.Test;

import com.github.tomakehurst.wiremock.stubbing.StubMapping;

import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;

public class FeeFineReportsAPITest extends ApiTests {
  private static final String USER_ID = randomId();
  private static final String START_DATE = "2020-01-01";
  private static final String END_DATE = "2020-01-15";
  private static final DateTimeZone TENANT_TZ = DateTimeZone.forID("America/New_York");

  private static final String PAYMENT_METHOD = "payment-method";
  private static final String REFUND_REASON = "refund-reason";
  private static final String TRANSFER_ACCOUNT = "Bursar";

  private static final String PAID_PARTIALLY = "Paid partially";
  private static final String PAID_FULLY = "Paid fully";
  private static final String TRANSFERRED_PARTIALLY = "Transferred partially";
  private static final String TRANSFERRED_FULLY = "Transferred fully";
  private static final String REFUNDED_PARTIALLY = "Refunded partially";
  private static final String REFUNDED_FULLY = "Refunded fully";

  private static final String PAYMENT_STAFF_INFO = "Payment - info for staff";
  private static final String PAYMENT_PATRON_INFO = "Payment - info for patron";
  private static final String REFUND_STAFF_INFO = "Refund - info for staff";
  private static final String REFUND_PATRON_INFO = "Refund - info for patron";

  private static final String PAYMENT_TX_INFO = "Payment transaction information";
  private static final String REFUND_TX_INFO = "Refund transaction information";
  private static final String TRANSFER_TX_INFO = "Transfer transaction information";

  private static final String SEE_FEE_FINE_PAGE = "See Fee/fine details page";

  private static final DateTimeFormatter dateTimeFormatter =
    DateTimeFormat.forPattern("M/d/yyyy K:mm a");

  private ResourceClient refundReportsClient;

  private UserGroup userGroup;
  private User user;
  private Item item;
  private Instance instance;

  private StubMapping localeSettingsStubMapping;

  @Before
  public void setUp() {
    removeAllFromTable(FEEFINES_TABLE);
    removeAllFromTable(ACCOUNTS_TABLE);
    removeAllFromTable(FEE_FINE_ACTIONS_TABLE);

    refundReportsClient = buildRefundReportClient();

    final KvConfigurations localeSettingsConfigurations = createLocaleSettingsConfigurations();
    localeSettingsStubMapping = createStubForPath(ServicePath.CONFIGURATION_ENTRIES,
      localeSettingsConfigurations, ".*");

    final Library library = createLibrary();
    final Campus campus = createCampus();
    final Institution institution = createInstitution();
    final Location location = createLocation(library, campus, institution);
    instance = createInstance();
    final HoldingsRecord holdingsRecord = createHoldingsRecord(instance);
    item = createItem(holdingsRecord, location);

    createStub(ServicePath.ITEMS_PATH, item, item.getId());
    createStub(HOLDINGS_PATH, holdingsRecord, holdingsRecord.getId());
    createStub(INSTANCES_PATH, instance, instance.getId());

    userGroup = EntityBuilder.createUserGroup();
    createStub(USERS_GROUPS_PATH, userGroup, userGroup.getId());

    user = EntityBuilder.createUser()
      .withId(USER_ID)
      .withPatronGroup(userGroup.getId());
    createStub(USERS_PATH, user, USER_ID);
  }

  @Test
  public void emptyRefundReportNoTimezone() {
    getOkapi().removeStub(localeSettingsStubMapping);

    requestAndCheck(List.of());
  }

  @Test
  public void emptyRefundReportTenantTimezone() {
    requestAndCheck(List.of());
  }

  @Test
  public void emptyReportWhenRefundedAfterEndDate() {
    Account account = charge(10.0, "ff-type", null);

    createAction(1, account, "2020-01-02 12:00:00", PAID_PARTIALLY, PAYMENT_METHOD,
      3.0, 7.0, PAYMENT_STAFF_INFO, PAYMENT_PATRON_INFO, PAYMENT_TX_INFO);

    createAction(1, account, "2020-02-01 12:00:00", REFUNDED_PARTIALLY, REFUND_REASON,
      2.0, 7.0, REFUND_STAFF_INFO, REFUND_PATRON_INFO,
      REFUND_TX_INFO);

    requestAndCheck(List.of());
  }

  @Test
  public void internalServerErrorWhenAccountIsDeleted() {
    Account account = charge(10.0, "ff-type", item.getId());

    createAction(1, account, "2020-01-01 12:00:00", PAID_PARTIALLY, PAYMENT_METHOD,
      3.0, 7.0, PAYMENT_STAFF_INFO, PAYMENT_PATRON_INFO, PAYMENT_TX_INFO);

    createAction(1, account, "2020-01-02 12:00:00",
      REFUNDED_PARTIALLY, REFUND_REASON, 2.0, 7.0, REFUND_STAFF_INFO, REFUND_PATRON_INFO,
      REFUND_TX_INFO);

    deleteEntity(ACCOUNTS_PATH, account.getId());

    refundReportsClient.getByDateInterval(START_DATE, END_DATE, HTTP_INTERNAL_SERVER_ERROR)
      .then()
      .body(is("Internal server error"));
  }

  @Test
  public void badRequestWhenParameterIsMissingOrMalformed() {
    refundReportsClient.getByParameters("startDate=2020-01-01", HTTP_BAD_REQUEST);
    refundReportsClient.getByParameters("endDate=2020-01-01", HTTP_BAD_REQUEST);
    refundReportsClient.getByParameters("startDate=not-a-date", HTTP_BAD_REQUEST);
    refundReportsClient.getByParameters("endDate=not-a-date", HTTP_BAD_REQUEST);
    refundReportsClient.getByParameters("startDate=2020-01-01&endDate=not-a-date", HTTP_BAD_REQUEST);
    refundReportsClient.getByParameters("startDate=not-a-date&endDate=2020-01-01", HTTP_BAD_REQUEST);
    refundReportsClient.getByParameters("startDate=not-a-date&endDate=not-a-date", HTTP_BAD_REQUEST);
  }

  @Test
  public void partiallyRefundedWithNoItem() {
    Account account = charge(10.0, "ff-type", null);

    createAction(1, account, "2020-01-02 12:00:00", PAID_PARTIALLY, PAYMENT_METHOD,
      3.0, 7.0, PAYMENT_STAFF_INFO, PAYMENT_PATRON_INFO, PAYMENT_TX_INFO);

    Feefineaction refundAction = createAction(1, account, "2020-01-03 12:00:00",
      REFUNDED_PARTIALLY, REFUND_REASON, 2.0, 7.0, REFUND_STAFF_INFO, REFUND_PATRON_INFO,
      REFUND_TX_INFO);

    requestAndCheck(List.of(
      buildRefundReportEntry(account, refundAction,
        "3.00", PAYMENT_METHOD, PAYMENT_TX_INFO, "0.00", "",
        addSuffix(REFUND_STAFF_INFO, 1), addSuffix(REFUND_PATRON_INFO, 1), "", "")
    ));
  }

  @Test
  public void partiallyRefundedWithItem() {
    Account account = charge(10.0, "ff-type", item.getId());

    createAction(1, account, "2020-01-02 12:00:00", PAID_PARTIALLY, PAYMENT_METHOD,
      3.0, 7.0, PAYMENT_STAFF_INFO, PAYMENT_PATRON_INFO, PAYMENT_TX_INFO);

    Feefineaction refundAction = createAction(1, account, "2020-01-03 12:00:00",
      REFUNDED_PARTIALLY, REFUND_REASON, 2.0, 7.0, REFUND_STAFF_INFO, REFUND_PATRON_INFO,
      REFUND_TX_INFO);

    requestAndCheck(List.of(
      buildRefundReportEntry(account, refundAction, "3.00", PAYMENT_METHOD, PAYMENT_TX_INFO,
        "0.00", "", addSuffix(REFUND_STAFF_INFO, 1), addSuffix(REFUND_PATRON_INFO, 1),
        item.getBarcode(), instance.getTitle())
    ));
  }

  @Test
  public void fullyRefundedTimeZoneTest() {
    Account account = charge(10.0, "ff-type", item.getId());

    createAction(1, account, "2020-01-01 01:00:00", PAID_PARTIALLY, PAYMENT_METHOD,
      3.0, 7.0, PAYMENT_STAFF_INFO, PAYMENT_PATRON_INFO, PAYMENT_TX_INFO);

    Feefineaction refundAction = createAction(1, account, "2020-01-03 12:00:00",
      REFUNDED_FULLY, REFUND_REASON, 3.0, 7.0, REFUND_STAFF_INFO, REFUND_PATRON_INFO,
      REFUND_TX_INFO);

    requestAndCheck(List.of(
      buildRefundReportEntry(account, refundAction,
        "3.00", PAYMENT_METHOD, PAYMENT_TX_INFO, "0.00", "",
        addSuffix(REFUND_STAFF_INFO, 1), addSuffix(REFUND_PATRON_INFO, 1),
        item.getBarcode(), instance.getTitle())
    ));
  }

  @Test
  public void multiplePaymentsSameMethodFullyRefunded() {
    Account account = charge(10.0, "ff-type", item.getId());

    createAction(1, account, "2020-01-01 12:00:00", PAID_PARTIALLY, PAYMENT_METHOD,
      3.1, 6.9, PAYMENT_STAFF_INFO, PAYMENT_PATRON_INFO, PAYMENT_TX_INFO);

    createAction(2, account, "2020-01-02 12:00:00", PAID_PARTIALLY, PAYMENT_METHOD,
      2.1, 4.8, PAYMENT_STAFF_INFO, PAYMENT_PATRON_INFO, PAYMENT_TX_INFO);

    Feefineaction refundAction = createAction(1, account, "2020-01-03 12:00:00",
      REFUNDED_FULLY, REFUND_REASON, 5.2, 4.8, REFUND_STAFF_INFO, REFUND_PATRON_INFO,
      REFUND_TX_INFO);

    requestAndCheck(List.of(
      buildRefundReportEntry(account, refundAction,
        "5.20", PAYMENT_METHOD, PAYMENT_TX_INFO, "0.00", "",
        addSuffix(REFUND_STAFF_INFO, 1), addSuffix(REFUND_PATRON_INFO, 1),
        item.getBarcode(), instance.getTitle())
    ));
  }

  @Test
  public void multiplePaymentMethodsFullyRefunded() {
    Account account = charge(10.0, "ff-type", item.getId());

    createAction(1, account, "2020-01-01 12:00:00", PAID_PARTIALLY, PAYMENT_METHOD,
      3.1, 6.9, PAYMENT_STAFF_INFO, PAYMENT_PATRON_INFO, PAYMENT_TX_INFO);

    createAction(2, account, "2020-01-02 12:00:00",
      PAID_PARTIALLY, PAYMENT_METHOD + "-different-method", 2.1, 4.8,
      PAYMENT_STAFF_INFO, PAYMENT_PATRON_INFO, PAYMENT_TX_INFO);

    Feefineaction refundAction = createAction(1, account, "2020-01-03 12:00:00",
      REFUNDED_FULLY, REFUND_REASON, 5.2, 4.8, REFUND_STAFF_INFO, REFUND_PATRON_INFO,
      REFUND_TX_INFO);

    requestAndCheck(List.of(
      buildRefundReportEntry(account, refundAction,
        "5.20", SEE_FEE_FINE_PAGE, PAYMENT_TX_INFO, "0.00", "",
        addSuffix(REFUND_STAFF_INFO, 1), addSuffix(REFUND_PATRON_INFO, 1),
        item.getBarcode(), instance.getTitle())
    ));
  }

  @Test
  public void partiallyTransferredFullyRefunded() {
    Account account = charge(10.0, "ff-type", item.getId());

    createAction(1, account, "2020-01-01 12:00:00", PAID_PARTIALLY, PAYMENT_METHOD,
      3.0, 7.0, PAYMENT_STAFF_INFO, PAYMENT_PATRON_INFO, PAYMENT_TX_INFO);

    createAction(1, account, "2020-01-02 12:00:00",
      TRANSFERRED_PARTIALLY, TRANSFER_ACCOUNT, 1.5, 8.5, "", "", TRANSFER_TX_INFO);

    Feefineaction refundAction = createAction(1, account, "2020-01-03 12:00:00",
      REFUNDED_PARTIALLY, REFUND_REASON, 1.0, 8.5, REFUND_STAFF_INFO, REFUND_PATRON_INFO,
      REFUND_TX_INFO);

    requestAndCheck(List.of(
      buildRefundReportEntry(account, refundAction,
        "3.00", PAYMENT_METHOD, PAYMENT_TX_INFO, "1.50", TRANSFER_ACCOUNT,
        addSuffix(REFUND_STAFF_INFO, 1), addSuffix(REFUND_PATRON_INFO, 1),
        item.getBarcode(), instance.getTitle())
    ));
  }

  @Test
  public void multipleAccountMultipleRefunds() {
    Account account1 = charge(10.0, "ff-type-1", item.getId());

    createAction(1, account1, "2020-01-01 12:00:00", PAID_PARTIALLY, PAYMENT_METHOD,
      3.1, 6.9, PAYMENT_STAFF_INFO, PAYMENT_PATRON_INFO, PAYMENT_TX_INFO);

    createAction(2, account1, "2020-01-02 12:00:00",
      PAID_PARTIALLY, PAYMENT_METHOD,
      3.2, 3.7, PAYMENT_STAFF_INFO, PAYMENT_PATRON_INFO, PAYMENT_TX_INFO + "-different-info");

    createAction(1, account1, "2020-01-03 12:00:00",
      TRANSFERRED_PARTIALLY, TRANSFER_ACCOUNT, 2.0, 5.7, "", "", TRANSFER_TX_INFO);

    Feefineaction refundAction1 = createAction(1, account1, "2020-01-04 12:00:00",
      REFUNDED_PARTIALLY, REFUND_REASON, 1.0, 5.7, REFUND_STAFF_INFO, REFUND_PATRON_INFO,
      REFUND_TX_INFO);

    createAction(2, account1, "2020-01-05 12:00:00",
      PAID_FULLY, PAYMENT_METHOD + "-different-method",
      5.7, 0.0, PAYMENT_STAFF_INFO, PAYMENT_PATRON_INFO, REFUND_TX_INFO);

    Feefineaction refundAction2 = createAction(2, account1, "2020-01-06 12:00:00",
      REFUNDED_FULLY, REFUND_REASON, 9.0, 0.0, REFUND_STAFF_INFO, REFUND_PATRON_INFO,
      REFUND_TX_INFO);

    Account account2 = charge(20.0, "ff-type-2", null);

    createAction(1, account2, "2020-01-07 12:00:00", PAID_PARTIALLY, PAYMENT_METHOD,
      17.0, 3.0, PAYMENT_STAFF_INFO, PAYMENT_PATRON_INFO, PAYMENT_TX_INFO);

    Feefineaction refundAction3 = createAction(1, account2, "2020-01-08 12:00:00",
      REFUNDED_FULLY, REFUND_REASON, 17.0, 3.0, REFUND_STAFF_INFO, REFUND_PATRON_INFO,
      REFUND_TX_INFO);

    requestAndCheck(List.of(
      buildRefundReportEntry(account1, refundAction1,
        "6.30", PAYMENT_METHOD, SEE_FEE_FINE_PAGE, "2.00", TRANSFER_ACCOUNT,
        addSuffix(REFUND_STAFF_INFO, 1), addSuffix(REFUND_PATRON_INFO, 1),
        item.getBarcode(), instance.getTitle()),
      buildRefundReportEntry(account1, refundAction2,
        "12.00", SEE_FEE_FINE_PAGE, SEE_FEE_FINE_PAGE, "2.00", TRANSFER_ACCOUNT,
        addSuffix(REFUND_STAFF_INFO, 2), addSuffix(REFUND_PATRON_INFO, 2),
        item.getBarcode(), instance.getTitle()),
      buildRefundReportEntry(account2, refundAction3,
        "17.00", PAYMENT_METHOD, PAYMENT_TX_INFO, "0.00", "",
        addSuffix(REFUND_STAFF_INFO, 1), addSuffix(REFUND_PATRON_INFO, 1),
        "", "")
    ));
  }

  private void requestAndCheck(List<RefundReportEntry> reportEntries) {
    ValidatableResponse response = requestRefundReport(START_DATE, END_DATE).then()
      .statusCode(HttpStatus.SC_OK)
      .body("reportData", iterableWithSize(reportEntries.size()));

    IntStream.range(0, reportEntries.size())
      .forEach(index -> response.body(format("reportData[%d]", index),
        refundReportEntryMatcher(reportEntries.get(index))));
  }

  private Account charge(Double amount, String feeFineType, String itemId) {
    final var account = EntityBuilder.buildAccount(USER_ID, itemId, feeFineType, amount);
    createEntity(ACCOUNTS_PATH, account);
    return accountsClient.getById(account.getId()).as(Account.class);
  }

  private Feefineaction createAction(int actionCounter, Account account, String dateTime,
    String type, String method, Double amount, Double balance, String staffInfo,
    String patronInfo, String txInfo) {

    Feefineaction action = EntityBuilder.buildFeeFineAction(USER_ID, account.getId(),
      type, method, amount, balance, parseDateTime(dateTime),
      addSuffix(staffInfo, actionCounter), addSuffix(patronInfo, actionCounter))
      .withTransactionInformation(txInfo);

    createEntity(ServicePath.ACTIONS_PATH, action);

    return action;
  }

  private RefundReportEntry buildRefundReportEntry(Account account,
    Feefineaction refundAction, String paidAmount, String paymentMethod, String transactionInfo,
    String transferredAmount, String transferAccount, String staffInfo, String patronInfo,
    String itemBarcode, String instance) {

    return new RefundReportEntry()
      .withPatronName(format("%s, %s %s", user.getPersonal().getLastName(),
        user.getPersonal().getFirstName(), user.getPersonal().getMiddleName()))
      .withPatronBarcode(user.getBarcode())
      .withPatronId(user.getId())
      .withPatronGroup(userGroup.getGroup())
      .withFeeFineType(account.getFeeFineType())
      .withBilledAmount(formatMonetaryValue(account.getAmount()))
      .withDateBilled(formatRefundReportDate(account.getMetadata().getCreatedDate(), TENANT_TZ))
      .withPaidAmount(paidAmount)
      .withPaymentMethod(paymentMethod)
      .withTransactionInfo(transactionInfo)
      .withTransferredAmount(transferredAmount)
      .withTransferAccount(transferAccount)
      .withFeeFineId(account.getId())
      .withRefundDate(formatRefundReportDate(refundAction.getDateAction(), TENANT_TZ))
      .withRefundAmount(formatMonetaryValue(refundAction.getAmountAction()))
      .withRefundAction(refundAction.getTypeAction())
      .withRefundReason(refundAction.getPaymentMethod())
      .withStaffInfo(staffInfo)
      .withPatronInfo(patronInfo)
      .withItemBarcode(itemBarcode)
      .withInstance(instance)
      .withActionCompletionDate("")
      .withStaffMemberName("")
      .withActionTaken("");
  }

  private String addSuffix(String info, int counter) {
    return format("%s %d", info, counter);
  }

  private Response requestRefundReport(String startDate, String endDate) {
    return refundReportsClient.getByDateInterval(startDate, endDate);
  }

  private String formatMonetaryValue(Double value) {
    return new MonetaryValue(value).toString();
  }

  private String formatRefundReportDate(Date date, DateTimeZone timeZone) {
    return new DateTime(date).withZone(timeZone).toString(dateTimeFormatter);
  }

  private Date parseDateTime(String date) {
    return DateTime.parse(date, DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss")).toDate();
  }
}
