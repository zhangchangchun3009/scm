
package pers.zcc.scm.common.excel.impl;

import java.beans.PropertyDescriptor;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Resource;
import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.SystemUtils;
import org.apache.poi.hssf.usermodel.HSSFCell;
import org.apache.poi.hssf.usermodel.HSSFCellStyle;
import org.apache.poi.hssf.usermodel.HSSFFont;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hssf.util.HSSFColor;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;

import pers.zcc.scm.common.constant.AsyncTaskStatusEnum;
import pers.zcc.scm.common.constant.AsyncTaskTypeEnum;
import pers.zcc.scm.common.excel.interfaces.ExcelMapConfigException;
import pers.zcc.scm.common.excel.interfaces.IExcelExportAssistant;
import pers.zcc.scm.common.excel.interfaces.IExcelExportDataProvider;
import pers.zcc.scm.common.excel.vo.ExcelExportMapVO;
import pers.zcc.scm.common.excel.vo.ExcelExportVO;
import pers.zcc.scm.common.service.IAsyncTaskEventResultService;
import pers.zcc.scm.common.vo.AsyncTaskEventResultVO;
import pers.zcc.scm.common.vo.BatchVO;

/**
 * The Class ExcelExportAssistant.
 *
 * @author zhangchangchun
 * @since 2021???4???8???
 */
@Named
public class ExcelExportAssistant implements IExcelExportAssistant {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExcelExportAssistant.class);

    /**
     * ????????????????????????
     */
    private static final int PAGESIZE = 500;

    @Resource
    @Named("threadPoolExecutor")
    private ThreadPoolTaskExecutor executor;

    @Inject
    private IAsyncTaskEventResultService asyncTaskService;

    /**
     * ??????????????????????????????????????????????????????excel??????
     * ??????????????????????????????????????????????????????????????????????????????
     *
     * @param request http??????
     * @param response the http??????
     * @param excelExportVO ????????????????????????????????????????????????????????????????????????
     * @throws Exception ????????????
     */
    @Override
    public String export(HttpServletRequest request, HttpServletResponse response, final ExcelExportVO excelExportVO)
            throws Exception {
//        response.setContentType("application/vnd.ms-excel");
        String fileName = excelExportVO.getFileName() + "_" + new SimpleDateFormat("yyyyMMddHHmmss").format(new Date())
                + ".xls";
//        response.setHeader("Content-disposition", "attachment;filename=" + URLEncoder.encode(fileName, "utf-8"));

        long eventId = createAsyncTask(excelExportVO);
        Runnable task = new Runnable() {
            @Override
            public void run() {
                asyncTaskService.updateAsyncTaskStatus(eventId, AsyncTaskStatusEnum.RUNNING);
                HSSFWorkbook workbook = new HSSFWorkbook();
                HSSFCellStyle headStyle = setHeaderStyle(workbook);
                HSSFCellStyle dataStyle = setDataStyle(workbook);

                HSSFSheet sheet = workbook.createSheet(excelExportVO.getSheetName());

                List<ExcelExportMapVO> headerMap = excelExportVO.getExportMapList();

                setHeaderRow(headStyle, sheet, headerMap);

                PageHelper.startPage(1, PAGESIZE);
                try {
                    Page<?> dataList1 = getData(excelExportVO);
                    if (dataList1.getTotal() < 1) {
                        asyncTaskService.updateAsyncTaskStatus(eventId, AsyncTaskStatusEnum.COMPLETED);
                        return;
                    }
                    setBatchDataRow(dataStyle, sheet, headerMap, dataList1, 1);

                    int pages = dataList1.getPages();
                    for (int i = 2; i <= pages; i++) {
                        PageHelper.startPage(i, PAGESIZE);
                        Page<?> dataListn = getData(excelExportVO);
                        setBatchDataRow(dataStyle, sheet, headerMap, dataListn, i);
                    }
                } catch (ExcelMapConfigException e) {
                    LOGGER.error("ExcelMapConfigException", e);
                    asyncTaskService.updateAsyncTaskStatus(eventId, AsyncTaskStatusEnum.STOPPED);
                    return;
                }

                try {
//                  workbook.write(response.getOutputStream());
                    String dir = (SystemUtils.IS_OS_WINDOWS ? "D:" : "/usr/zcc") + File.separator + "excel"
                            + File.separator + "export";
                    File direc = new File(dir);
                    if (!direc.exists()) {
                        direc.mkdirs();
                    }
                    String filePath = dir + File.separator + fileName;
                    workbook.write(new File(filePath));
                    workbook.close();
                    asyncTaskService.updateAsyncTaskStatus(eventId, AsyncTaskStatusEnum.COMPLETED);
                } catch (IOException e) {
                    asyncTaskService.updateAsyncTaskStatus(eventId, AsyncTaskStatusEnum.STOPPED);
                    return;
                }
                return;
            }
        };
        CompletableFuture.runAsync(task, executor).whenComplete((res, ex) -> {
            if (ex != null) {
                asyncTaskService.updateAsyncTaskStatus(eventId, AsyncTaskStatusEnum.STOPPED);
            }
        });
        return fileName;
    }

    private Long createAsyncTask(ExcelExportVO exportContext) {
        Long eventId = asyncTaskService.gennerateTaskId();
        String fileName = exportContext.getFileName();
        long now = System.currentTimeMillis();
        String taskName = fileName + "_" + now;
        AsyncTaskEventResultVO task = new AsyncTaskEventResultVO();
        task.setId(eventId);
        task.setTaskName(taskName);
        task.setStartTime(now);
        task.setStatus(AsyncTaskStatusEnum.CREATED.getValue());
        task.setEventType(AsyncTaskTypeEnum.EXPORT.getValue());
        task.setCreatedBy(exportContext.getUser().getUserId());
        BatchVO<AsyncTaskEventResultVO> batchVO = new BatchVO<>();
        List<AsyncTaskEventResultVO> itemsToCreate = new ArrayList<>();
        itemsToCreate.add(task);
        batchVO.setItemsToCreate(itemsToCreate);
        asyncTaskService.batch(batchVO);
        return eventId;
    }

    private static void setHeaderRow(HSSFCellStyle headStyle, HSSFSheet sheet, List<ExcelExportMapVO> headerMap) {
        HSSFRow row0 = sheet.createRow(0);
        for (int i = 0; i < headerMap.size(); i++) {
            ExcelExportMapVO map = headerMap.get(i);
            sheet.setColumnWidth(i, map.getColumnWidth());
            HSSFCell cell = row0.createCell(i);
            cell.setCellValue(map.getColumn());
            cell.setCellStyle(headStyle);
        }
    }

    private static void setBatchDataRow(HSSFCellStyle dataStyle, HSSFSheet sheet, List<ExcelExportMapVO> headerMap,
            Page<?> dataList, int pageNum) throws ExcelMapConfigException {
        for (int i = 0; i < dataList.size(); i++) {
            HSSFRow row = sheet.createRow((pageNum - 1) * PAGESIZE + i + 1);
            Object data = dataList.get(i);
            for (int j = 0; j < headerMap.size(); j++) {
                ExcelExportMapVO map = headerMap.get(j);
                HSSFCell cell = row.createCell(j);
                PropertyDescriptor pd = BeanUtils.getPropertyDescriptor(data.getClass(), map.getProperty());
                String propertyValue = "";
                try {
                    propertyValue = (String) pd.getReadMethod().invoke(data, new Object[] {});
                } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                    throw new ExcelMapConfigException("excel ??????????????????");
                }
                cell.setCellValue(propertyValue);
                cell.setCellStyle(dataStyle);
            }
        }
    }

    private static Page<?> getData(ExcelExportVO excelExportVO) throws ExcelMapConfigException {
        Object dao = excelExportVO.getQueryDao();
        String queryMethodName = excelExportVO.getQueryMethodName();
        List<Object> queryParamList = excelExportVO.getQueryParamList();
        Class<?>[] parameterTypes = null;
        Object[] parameterValues = null;
        if (queryParamList != null && !queryParamList.isEmpty()) {
            parameterTypes = new Class<?>[queryParamList.size()];
            parameterValues = new Object[queryParamList.size()];
            for (int i = 0; i < queryParamList.size(); i++) {
                parameterTypes[i] = queryParamList.get(i).getClass();
                parameterValues[i] = queryParamList.get(i);
            }
        }
        Object dataS = null;
        try {
            Method method = dao.getClass().getDeclaredMethod(queryMethodName, parameterTypes);
            dataS = method.invoke(dao, parameterValues);
        } catch (NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException
                | InvocationTargetException e) {
            throw new ExcelMapConfigException("excel??????????????????????????????????????????");
        }
        if (dataS == null) {
            return new Page<Object>();
        }
        Page<?> dataList = (Page<?>) dataS;
        return dataList;
    }

    private static HSSFCellStyle setHeaderStyle(HSSFWorkbook workbook) {
        HSSFCellStyle headStyle = workbook.createCellStyle(); // ??????
        HSSFFont headFont = workbook.createFont(); // ????????????
        headFont.setFontName("??????");
        headFont.setBold(true); // ??????
        headFont.setFontHeightInPoints((short) 14); // ??????
        headStyle.setFont(headFont);
        headStyle.setBorderTop(BorderStyle.THIN);
        headStyle.setBorderRight(BorderStyle.THIN);
        headStyle.setBorderLeft(BorderStyle.THIN);
        headStyle.setBorderBottom(BorderStyle.THIN);
        headStyle.setAlignment(HorizontalAlignment.CENTER); // ????????????
        headStyle.setVerticalAlignment(VerticalAlignment.CENTER); // ????????????
        headStyle.setWrapText(true); // ????????????
        headStyle.setFillBackgroundColor(HSSFColor.HSSFColorPredefined.LIGHT_GREEN.getIndex());
        headStyle.setFillForegroundColor(HSSFColor.HSSFColorPredefined.LIGHT_GREEN.getIndex());
        headStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return headStyle;
    }

    private static HSSFCellStyle setDataStyle(HSSFWorkbook workbook) {
        HSSFCellStyle dataStyle = workbook.createCellStyle(); // ??????
        HSSFFont dataFont = workbook.createFont(); // ????????????
        dataFont.setFontName("??????");
        dataFont.setBold(false); // ??????
        dataFont.setFontHeightInPoints((short) 10); // ??????
        dataStyle.setFont(dataFont);
        dataStyle.setBorderTop(BorderStyle.THIN);
        dataStyle.setBorderRight(BorderStyle.THIN);
        dataStyle.setBorderLeft(BorderStyle.THIN);
        dataStyle.setBorderBottom(BorderStyle.THIN);
        dataStyle.setAlignment(HorizontalAlignment.LEFT); // ????????????
        dataStyle.setVerticalAlignment(VerticalAlignment.CENTER); // ????????????
        dataStyle.setWrapText(true); // ????????????
        dataStyle.setFillBackgroundColor(HSSFColor.HSSFColorPredefined.LIGHT_YELLOW.getIndex());
        dataStyle.setFillForegroundColor(HSSFColor.HSSFColorPredefined.LIGHT_YELLOW.getIndex());
        dataStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return dataStyle;
    }

    /**
     * ???????????????????????????excel
     * ?????????????????????D:\excel\export??????
     * ?????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
     * ??????????????????????????????????????????
     *
     * @param <T> the generic type ??????????????????java??????
     * @param provider the provider ???????????????????????????????????????????????????????????????
     * @param excelExportVO ????????????????????????????????????????????????????????????
     * @return ????????????????????????????????????????????????????????????????????????
     * @throws Exception the exception ????????????
     */
    @Override
    public <T> String export(IExcelExportDataProvider<T> provider, final ExcelExportVO excelExportVO) throws Exception {
        String fileName = excelExportVO.getFileName() + "_" + new SimpleDateFormat("yyyyMMddHHmmss").format(new Date())
                + ".xls";
        HSSFWorkbook workbook = new HSSFWorkbook();
        HSSFCellStyle headStyle = setHeaderStyle(workbook);
        HSSFCellStyle dataStyle = setDataStyle(workbook);

        HSSFSheet sheet = workbook.createSheet(excelExportVO.getSheetName());

        List<ExcelExportMapVO> headerMap = excelExportVO.getExportMapList();

        ArrayList<T> dataList = provider.getData();
        if (dataList == null || dataList.size() < 1) {
            return "";
        }

        setHeaderRow(headStyle, sheet, headerMap);

        setBatchDataRow(dataStyle, sheet, headerMap, dataList);
        try {
            String dir = (SystemUtils.IS_OS_WINDOWS ? "D:" : "/usr/zcc") + File.separator + "excel" + File.separator
                    + "export";
            File direc = new File(dir);
            if (!direc.exists()) {
                direc.mkdirs();
            }
            String filePath = dir + File.separator + fileName;
            workbook.write(new File(filePath));
            workbook.close();
            return fileName;
        } catch (IOException e) {
            throw e;
        }
    }

    private static <T> void setBatchDataRow(HSSFCellStyle dataStyle, HSSFSheet sheet, List<ExcelExportMapVO> headerMap,
            ArrayList<T> dataList) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        for (int i = 0; i < dataList.size(); i++) {
            HSSFRow row = sheet.createRow(i + 1);
            Object data = dataList.get(i);
            for (int j = 0; j < headerMap.size(); j++) {
                ExcelExportMapVO map = headerMap.get(j);
                HSSFCell cell = row.createCell(j);
                PropertyDescriptor pd = BeanUtils.getPropertyDescriptor(data.getClass(), map.getProperty());
                String propertyValue = (String) pd.getReadMethod().invoke(data, new Object[] {});
                cell.setCellValue(propertyValue);
                cell.setCellStyle(dataStyle);
            }
        }
    }

}
