package com.example.paevictions;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.hssf.usermodel.HSSFCreationHelper;
import org.apache.poi.hssf.usermodel.HSSFHyperlink;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@SpringBootApplication
public class PaEvictionsApplication implements CommandLineRunner {

    private final static Logger log = LoggerFactory.getLogger(PaEvictionsApplication.class);

    private final static DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final static String ALL_TARGET_COURTS = "all";

    @Value("${startDate}")
    private String startDate;

    @Value("${endDate}")
    private String endDate;

    @Value("${districtCourtId:all}")
    private String districtCourtId;

    public static void main(String[] args) {
        new SpringApplicationBuilder(PaEvictionsApplication.class)
            .logStartupInfo(false)
            .run(args);
    }

    @Override
    public void run(String... args) {
        List<String> htmlDocuments = searchDockets(LocalDate.parse(startDate, DATE_FORMATTER), LocalDate.parse(endDate, DATE_FORMATTER));
        if (CollectionUtils.isNotEmpty(htmlDocuments)) {
            log.info("Fetching docket PDFs, hang tight this may take several minutes...");

            Set<String> links = htmlDocuments.stream().flatMap(document -> extractDocketLinks(document, districtCourtId).stream()).collect(Collectors.toCollection(LinkedHashSet::new));
            AtomicInteger requestCounter = new AtomicInteger();
            SortedSet<Pair<String, List<String>>> dockets = links.stream()
                .map(url -> getDocket(url, requestCounter))
                .filter(Objects::nonNull)
                .map(PaEvictionsApplication::extractDocketContent)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
            if (CollectionUtils.isNotEmpty(dockets)) {
                log.info("Fetched and extracted content of docket PDFs");
                createExcelOutput(dockets, districtCourtId);
            }
            else {
                log.info("No dockets were filed between {} and {}", startDate, endDate);
            }
        }
        else {
            log.info("No dockets found");
        }
    }

    private static List<String> searchDockets(LocalDate startDate, LocalDate endDate) {
        List<Pair<LocalDate, LocalDate>> dates = new ArrayList<>();

        if (ChronoUnit.DAYS.between(startDate, endDate.plusDays(1)) > 180) {
            LocalDate periodStart = startDate;
            do {
                LocalDate periodEnd = periodStart.plusDays(180);
                if (periodEnd.isAfter(endDate)) {
                    dates.add(Pair.of(periodStart, endDate));
                }
                else {
                    dates.add(Pair.of(periodStart, periodEnd));
                }
                periodStart = periodEnd;
            } while (periodStart.isBefore(endDate));
        }
        else {
            dates.add(Pair.of(startDate, endDate));
        }

        List<String> result = dates.stream().map(period -> {
            String start = period.getLeft().format(DATE_FORMATTER);
            String end = period.getRight().format(DATE_FORMATTER);
            log.info("Searching for dockets between {} and {}", start, end);
            HttpResponse<String> response = Unirest.post("https://ujsportal.pacourts.us/CaseSearch")
                .contentType("application/x-www-form-urlencoded")
                .header("Cookie", "ASP.NET_SessionId=jicubri4orod4hscojxxkujw; f5avraaaaaaaaaaaaaaaa_session_=HABHNKGKALDCEKJPBCPNJBOCPPAHDPCJKKMFDOJNHJHOBMMMIAEAKLDJONCILKJCKHIDGACEOEFOONIJFMPANBLOPKLECBEABFLNJILPAECMGIHCABNJFAAMNHPFFEDB; .AspNetCore.Antiforgery.SBFfOFqeTDE=CfDJ8MpxQXjimVFCpy5zybqEOu1FpGha8XmJf8lD1bMjK1pajZ5A0CRE0f4xfx5HkRAmmnAjFTe_pSJQN07Ua5pMnHai9_6QIM1qnUdph2Dbsv-yY_sde6jqGZCXYl5_3_0NKaFBzb6DhfHKjZUwNCTn0cg")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-User", "?1")
                .body("SearchBy=DateFiled&AdvanceSearch=true&ParticipantSID=&ParticipantSSN=&FiledStartDate=" + start + "&FiledEndDate=" + end + "&County=Northampton&MDJSCourtOffice=&PADriversLicenseNumber=&CalendarEventStartDate=&CalendarEventEndDate=&CalendarEventType=&__RequestVerificationToken=CfDJ8MpxQXjimVFCpy5zybqEOu3NPM_E9zPEx9kjX1NPE2K1YDQD6eDlzmW_b6sYROBIQSNbEeR_1P23Y1sOkfTcALUrpN2FWmewttYlPzf9aF899voshfSBCo3s4pYjfEi0rkiC4wZcDVAL7gkW15M2_XQ")
                .asString();

            if (!response.isSuccess()) {
                log.error("Search for dockets between {} and {} failed, status {}, reason {}", start, end, response.getStatus(), response.getBody());
                return "<html></html>";
            }

            return response.getBody();
        }).collect(Collectors.toUnmodifiableList());


        log.info("Finished searching for dockets");
        return result;
    }

    private static Set<String> extractDocketLinks(String html, String districtCourtId) {
        Document document = Jsoup.parse(html);
        Elements elements;
        if (ALL_TARGET_COURTS.equals(districtCourtId)) {
            elements = document.select("[href^=/Report/MdjDocketSheet?docketNumber=MJ-03210-LT],[href^=/Report/MdjDocketSheet?docketNumber=MJ-03211-LT]");
        }
        else {
            elements = document.select("[href^=/Report/MdjDocketSheet?docketNumber=MJ-" + districtCourtId + "-LT]");
        }
        return elements.eachAttr("href").stream().collect(Collectors.toUnmodifiableSet());
    }

    private static Pair<String, byte[]> getDocket(String path, AtomicInteger requestCounter) {
        int counter = requestCounter.get();
        if (counter > 0 && counter % 15 == 0) {
            // API returns 429 if too many requests are made,
            try {
                log.info("Still working...");
                TimeUnit.SECONDS.sleep(45);
            } catch (InterruptedException ignored) {
            }
        }
        else if (counter > 0) {
            try {
                TimeUnit.SECONDS.sleep(2);
            } catch (InterruptedException ignored) {
            }
        }

        String address = "https://ujsportal.pacourts.us" + path;
        HttpResponse<byte[]> response = Unirest.get(address)
            .accept("application/pdf")
            .asBytes();

        if (response.getStatus() != 200) {
            log.error("Unable to fetch {}, status code {}, reason {} ", address, response.getStatus(), new String(response.getBody()));
            return null;

        }

        requestCounter.incrementAndGet();
        return Pair.of(path, response.getBody());
    }

    private static Pair<String, List<String>> extractDocketContent(Pair<String, byte[]> content) {
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler handler = new BodyContentHandler();
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();

        try {
            parser.parse(new ByteArrayInputStream(content.getRight()), handler, metadata, context);
            return Pair.of(content.getLeft(),
                new ArrayList<>(Arrays.asList(handler.toString().split("\n"))));
        } catch (Exception e) {
            log.error("Unable to extract content from {}", content.getLeft());
            return null;
        }
    }

    private void createExcelOutput(SortedSet<Pair<String, List<String>>> dockets, String districtCourtId) {
        log.info("Extracting content from docket PDFs and building a spreadsheet");
        HSSFWorkbook workbook = new HSSFWorkbook();
        HSSFCreationHelper createHelper = workbook.getCreationHelper();
        HSSFSheet sheet = workbook.createSheet("Landlord Tenant Dockets");

        CellStyle dateCellStyle = workbook.createCellStyle();
        dateCellStyle.setDataFormat(createHelper.createDataFormat().getFormat("MM/dd/yyyy"));

        CellStyle wrappedText = workbook.createCellStyle();
        wrappedText.setWrapText(true);


        createHeaderRow(sheet);
        int rowIndex = 1;
        for (Pair<String, List<String>> docket : dockets) {
            createRow(createHelper, wrappedText, dateCellStyle, sheet, docket, rowIndex);
            rowIndex++;
        }

        IntStream.rangeClosed(0, 8).forEach(sheet::autoSizeColumn);

        String filename;
        if (ALL_TARGET_COURTS.equals(districtCourtId)) {
            filename = System.getProperty("user.home") + "/Desktop/" + "multiple-magisterial-district-landlord-tenant-dockets-" + startDate + "_to_" + endDate + ".xls";
        }
        else {
            filename = System.getProperty("user.home") + "/Desktop/" + "magisterial-district-judge-" + districtCourtId + "-landlord-tenant-dockets-" + startDate + "_to_" + endDate + ".xls";
        }

        try (OutputStream fileOut = new FileOutputStream(filename)) {
            workbook.write(fileOut);
        } catch (Exception e) {
            log.error("Unable to create file", e);
        }
    }

    private void createHeaderRow(HSSFSheet sheet) {
        Row row = sheet.createRow(0);
        row.createCell(0, CellType.STRING).setCellValue("Docket #");
        row.createCell(1, CellType.STRING).setCellValue("Date filed");
        row.createCell(2, CellType.STRING).setCellValue("Date resolved (Disposition Date)");
        row.createCell(3, CellType.STRING).setCellValue("Winner");
        row.createCell(4, CellType.STRING).setCellValue("Judgement ($)");
        row.createCell(5, CellType.STRING).setCellValue("Plaintiff");
        row.createCell(6, CellType.STRING).setCellValue("Defendant");
        row.createCell(7, CellType.STRING).setCellValue("Monthly Rent");
        row.createCell(8, CellType.STRING).setCellValue("Docket URL");
    }

    private static void createRow(HSSFCreationHelper createHelper, CellStyle wrappedText, CellStyle dateCellStyle, HSSFSheet sheet, Pair<String, List<String>> docket, int rowIndex) {
        List<String> docketContent = docket.getRight();
        String defendantText = docketContent.stream()
            .filter(d -> d.startsWith("Defendant "))
            .map(d -> d.substring(10))
            .map(String::trim)
            .collect(Collectors.joining("\n"));

        if (defendantText.endsWith("18017")) {
            log.warn("Skipping docket because defendant's address in 18017. {}", "https://ujsportal.pacourts.us" + docket.getLeft());
            return;
        }

        Row row = sheet.createRow(rowIndex);
        row.createCell(0, CellType.STRING)
            .setCellValue(
                docketContent.stream()
                    .filter(d -> d.contains("Docket Number: "))
                    .findFirst()
                    .map(d -> d.split(":")[1])
                    .map(String::trim).orElse(StringUtils.EMPTY)
            );

        Cell dateFiled = row.createCell(1);
        dateFiled.setCellStyle(dateCellStyle);
        dateFiled.setCellValue(
            docketContent.stream()
                .filter(d -> d.contains("File Date:Magisterial District Judge"))
                .findFirst()
                .map(d -> d.substring(0, 10))
                .orElse(StringUtils.EMPTY)
        );

        Cell dateResolved = row.createCell(2);
        dateResolved.setCellStyle(dateCellStyle);
        dateResolved.setCellValue(
            docketContent.stream()
                .filter(d -> d.contains("Disposition Date:"))
                .findFirst()
                .map(d -> d.split(":")[1])
                .map(String::trim)
                .map(d -> d.substring(0, 11))
                .map(String::trim)
                .orElse(StringUtils.EMPTY)
        );

        row.createCell(3, CellType.STRING)
            .setCellValue(
                docketContent.stream()
                    .filter(d -> d.contains("Judgment for Plaintiff") || d.contains("Dismissed") || d.contains("Case Transferred"))
                    .findFirst()
                    .map(d -> {
                        if (d.contains("Plaintiff")) {
                            return "Plaintiff";
                        }
                        else if (d.contains("Dismissed")) {
                            return "Defendant";
                        }
                        else if (d.contains("Case Transferred")) {
                            return "Transferred to another Magisterial District Court";
                        }
                        else {
                            return null;
                        }
                    })

                    .orElse("Could not be determined, review Docket PDF")
            );

        row.createCell(4, CellType.NUMERIC)
            .setCellValue(
                docketContent.stream()
                    .filter(d -> d.contains("Judgment Amount:"))
                    .findFirst()
                    .filter(d -> d.indexOf('J') > 0)
                    .map(d -> d.substring(1, d.indexOf('J')))
                    .orElse(StringUtils.EMPTY)
            );


        Cell plaintiff = row.createCell(5, CellType.STRING);
        plaintiff.setCellStyle(wrappedText);
        plaintiff.setCellValue(
            docketContent.stream()
                .filter(d -> d.startsWith("Plaintiff "))
                .map(d -> d.substring(10))
                .map(String::trim)
                .collect(Collectors.joining("\n"))
        );

        Cell defendant = row.createCell(6, CellType.STRING);
        defendant.setCellStyle(wrappedText);
        defendant.setCellValue(defendantText);

        row.createCell(7, CellType.NUMERIC)
            .setCellValue(
                docketContent.stream()
                    .filter(d -> d.contains("Monthly Rent: "))
                    .findFirst()
                    .map(d -> d.substring(d.indexOf('$') + 1))
                    .map(String::trim)
                    .orElse(StringUtils.EMPTY)
            );

        String address = "https://ujsportal.pacourts.us" + docket.getLeft();
        HSSFHyperlink link = createHelper.createHyperlink(HyperlinkType.URL);
        link.setAddress(address);
        Cell url = row.createCell(8);
        url.setCellValue(address);
        url.setHyperlink(link);
    }
}
