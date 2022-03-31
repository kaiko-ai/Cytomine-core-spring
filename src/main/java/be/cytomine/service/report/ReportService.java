package be.cytomine.service.report;

import be.cytomine.exceptions.ServerException;
import be.cytomine.service.utils.ReportFormatService;
import be.cytomine.utils.DateUtils;
import be.cytomine.utils.StringUtils;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@AllArgsConstructor
public class ReportService {

    public static final boolean HAS_PAGINATION = true;
    public static final boolean HAS_HEADER = true;
    public static final List<ReportColumn> ANNOTATION_REPORT_COLUMNS = new ArrayList<>(){{
        add(new ReportColumn("id", "Id", (float) 0.05));
        add(new ReportColumn("area", "Area (microns²)", (float) 0.10));
        add(new ReportColumn("perimeter", "Perimeter (mm)", (float) 0.10));
        add(new ReportColumn("X", "X", (float) 0.05));
        add(new ReportColumn("Y", "Y", (float) 0.05));
        add(new ReportColumn("image", "Image Id", (float) 0.07));
        add(new ReportColumn("filename", "Image Filename", (float) 0.10));
        add(new ReportColumn("user", "User", (float) 0.05));
        add(new ReportColumn("term", "Term", (float) 0.05));
        add(new ReportColumn("cropURL", "View annotation picture", (float) 0.19));
        add(new ReportColumn("imageURL", "View annotation on image", (float) 0.19));
    }};
    public static final List<ReportColumn> USER_REPORT_COLUMNS = new ArrayList<>(){{
        add(new ReportColumn("username", "User Name", (float) 0.33));
        add(new ReportColumn("firstname", "First Name", (float) 0.33));
        add(new ReportColumn("lastname", "Last Name", (float) 0.34));
    }};

    private final PDFReportService pdfReportService;

    private final SpreadsheetReportService spreadsheetReportService;

    private final ReportFormatService reportFormatService;

    public byte[] generateUsersReport(String projectName, List<Map<String, Object>> data, String format) throws ServerException {
        Object[][] dataForReport = reportFormatService.formatUsersForReport(USER_REPORT_COLUMNS, data);
        return generateReport(getUserReportTitle(projectName), dataForReport, USER_REPORT_COLUMNS, format);
    }

    public byte[] generateAnnotationsReport(String projectName, Set<String> terms, Set<String> users, List<Map<String, Object>> data, String format, boolean isReview) throws ServerException {
        Object[][] dataForReport = reportFormatService.formatAnnotationsForReport(ANNOTATION_REPORT_COLUMNS, data);
        return generateReport(getAnnotationReportTitle(projectName, terms, users), dataForReport, ANNOTATION_REPORT_COLUMNS, format);
    }

    public byte[] generateReport(String title, Object[][] data, List<ReportColumn> columns, String format) throws ServerException {
        float[] columnWidth = reportFormatService.getColumnWidth(columns);
        switch (format){
            case "csv": case "xls" :
                return spreadsheetReportService.writeSpreadsheet(data);
            case "pdf":
                return pdfReportService.writePDF(data, title, columnWidth, HAS_PAGINATION, HAS_HEADER);
            default :
                log.error("Format should be one of these types: 'pdf', 'csv' or 'xls'.");
                throw new ServerException("Failed to generate report, invalid format. Format should be one of these types: 'pdf', 'csv' or 'xls'.");
        }
    }

    private String getAnnotationReportTitle(String projectName, Set<String> terms, Set<String> users) {
        return "Annotations in " + projectName + " created by " + String.join(" or ", users) + " and associated with " + String.join(" or ", terms) + " @ " + DateUtils.getLocaleDate(new Date());
    }

    private String getUserReportTitle(String projectName) {
        return "User in " + projectName + " created @ " + DateUtils.getLocaleDate(new Date());
    }

    public String getAnnotationReportFileName(String format, Long projectId){
        return DateUtils.getSimpleFormatLocaleDate(new Date()) + "_annotations_project" + projectId + "." + format;
    }

    public String getUsersReportFileName(String format, Long projectId){
        return DateUtils.getSimpleFormatLocaleDate(new Date()) + "_users_project" + projectId + "." + format;
    }
}
