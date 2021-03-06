package net.gnehzr.tnoodle.server.webscrambles;

import com.itextpdf.awt.DefaultFontMapper;
import com.itextpdf.awt.PdfGraphics2D;
import com.itextpdf.text.BaseColor;
import com.itextpdf.text.Chunk;
import com.itextpdf.text.Document;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.Element;
import com.itextpdf.text.Font;
import com.itextpdf.text.Image;
import com.itextpdf.text.PageSize;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.Phrase;
import com.itextpdf.text.Rectangle;
import com.itextpdf.text.log.CounterFactory;
import com.itextpdf.text.log.NoOpCounter;
import com.itextpdf.text.pdf.BaseFont;
import com.itextpdf.text.pdf.ColumnText;
import com.itextpdf.text.pdf.PdfAction;
import com.itextpdf.text.pdf.PdfContentByte;
import com.itextpdf.text.pdf.PdfDestination;
import com.itextpdf.text.pdf.PdfImportedPage;
import com.itextpdf.text.pdf.PdfOutline;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfReader;
import com.itextpdf.text.pdf.PdfSmartCopy;
import com.itextpdf.text.pdf.PdfTemplate;
import com.itextpdf.text.pdf.PdfWriter;
import net.gnehzr.tnoodle.scrambles.InvalidScrambleException;
import net.gnehzr.tnoodle.scrambles.Puzzle;
import net.gnehzr.tnoodle.scrambles.PuzzlePlugins;
import net.gnehzr.tnoodle.scrambles.ScrambleCacher;
import net.gnehzr.tnoodle.svglite.Color;
import net.gnehzr.tnoodle.svglite.Dimension;
import net.gnehzr.tnoodle.svglite.Svg;
import net.gnehzr.tnoodle.utils.BadLazyClassDescriptionException;
import net.gnehzr.tnoodle.utils.LazyInstantiator;
import net.gnehzr.tnoodle.utils.Utils;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.io.ZipOutputStream;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.util.Zip4jConstants;
import org.apache.batik.bridge.BridgeContext;
import org.apache.batik.bridge.DocumentLoader;
import org.apache.batik.bridge.GVTBuilder;
import org.apache.batik.bridge.UserAgent;
import org.apache.batik.bridge.UserAgentAdapter;
import org.apache.batik.anim.dom.SAXSVGDocumentFactory;
import org.apache.batik.gvt.GraphicsNode;
import org.apache.batik.util.XMLResourceDescriptor;
import org.joda.time.DateTime;
import org.w3c.dom.svg.SVGDocument;

import javax.servlet.ServletContext;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.SortedMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import static net.gnehzr.tnoodle.utils.GsonUtils.GSON;
import static net.gnehzr.tnoodle.utils.GwtSafeUtils.*;
import static net.gnehzr.tnoodle.server.webscrambles.Translate.translate;
import static net.gnehzr.tnoodle.server.webscrambles.Utils.toFileSafeString;

class ScrambleRequest implements Comparable<ScrambleRequest> {
    private static final Logger l = Logger.getLogger(ScrambleRequest.class.getName());
    private static final String HTML_SCRAMBLE_VIEWER = "/wca/scrambleviewer.html";
    private static final int MAX_SCRAMBLES_PER_PAGE = 7;
    private static final int SCRAMBLE_IMAGE_PADDING = 2;
    private static final float MAX_SCRAMBLE_FONT_SIZE = 20;
    private static final float MINIMUM_ONE_LINE_FONT_SIZE = 12;
    private static final int MIN_LINES_TO_ALTERNATE_HIGHLIGHTING = 4;
    private static final BaseColor HIGHLIGHT_COLOR = new BaseColor(230, 230, 230);
    private static final int SCRAMBLE_PADDING_VERTICAL_TOP = 3;
    private static final int SCRAMBLE_PADDING_VERTICAL_BOTTOM = 6;
    private static final int SCRAMBLE_PADDING_HORIZONTAL = 1;
    private static final int TEXT_PADDING_HORIZONTAL = 1;

    private static final int MAX_COUNT = 100;
    private static final int MAX_COPIES = 100;

    private static final int WCA_MAX_MOVES_FMC = 80;

    private static final char NON_BREAKING_SPACE = '\u00A0';

    private static BaseFont monoFont, notoSans;
    private static HashMap<Locale, BaseFont> FONT_BY_LOCALE = new HashMap<Locale, BaseFont>();
    static {
        try {
            monoFont = BaseFont.createFont("fonts/LiberationMono-Regular.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED);

            BaseFont cjk = BaseFont.createFont("fonts/wqy-microhei.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            FONT_BY_LOCALE.put(Locale.forLanguageTag("zh-CN"), cjk);
            FONT_BY_LOCALE.put(Locale.forLanguageTag("zh-TW"), cjk);
            FONT_BY_LOCALE.put(Locale.forLanguageTag("ko"), cjk);
            FONT_BY_LOCALE.put(Locale.forLanguageTag("ja"), cjk);

            notoSans = BaseFont.createFont("fonts/NotoSans-Regular.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
        } catch (DocumentException e) {
            l.log(Level.INFO, "", e);
        } catch (IOException e) {
            l.log(Level.INFO, "", e);
        }

        // Email agpl@itextpdf.com if you want to know what this is about =)
        CounterFactory.getInstance().setCounter(new NoOpCounter());
    }

    private static BaseFont getFontForLocale(Locale locale) {
        return FONT_BY_LOCALE.getOrDefault(locale, notoSans);
    }

    private static HashMap<String, ScrambleCacher> scrambleCachers = new HashMap<String, ScrambleCacher>();
    private static SortedMap<String, LazyInstantiator<Puzzle>> puzzles;
    static {
        try {
            puzzles = PuzzlePlugins.getScramblers();
        } catch (BadLazyClassDescriptionException e) {
            l.log(Level.INFO, "", e);
        } catch (IOException e) {
            l.log(Level.INFO, "", e);
        }
    }

    // This is here just to make GSON work.
    public ScrambleRequest(){}

    public String[] scrambles;
    public String[] extraScrambles = new String[0];
    public Puzzle scrambler;
    public int copies;
    public String title;
    public boolean fmc;
    public HashMap<String, Color> colorScheme;
    public int totalAttempt;
    public int attempt;
    public DateTime roundStartTime;
    
    // totalAttempt and attempt are useful for when we have multiple attempts split in the schedule.
    // Usually, tnoodle prints scrambles for a ScrambleRequest iterating over ScrambleRequest.scrambles.
    // So, if ScrambleRequest.scrambles.length == 3, tnoodle prints Scramble 1 of 3, Scramble 2 of 3 and Scramble 3 of 3.
    // But for OrderedScrambles, these scrambles are split on the schedule, so we replace Scramble.scrambles = {Scramble.scrambles[attempt]}.
    // To continue printing Scramble x of y, we use attempt as x and totalAttempt as y.

    // The following attributes are here purely so the scrambler ui
    // can pass these straight to the generated JSON we put in the
    // zip file. This makes it easier to align that JSON with the rounds
    // of a competition.
    public String group; // This legacy field is still used by the WCA Workbook Assistant. When we get rid of the WA, we can get rid of this.
    public String scrambleSetId, event;
    public int round;

    public ScrambleRequest(String title, String scrambleRequestUrl, String seed) throws InvalidScrambleRequestException, UnsupportedEncodingException {
        String[] puzzle_count_copies_scheme = scrambleRequestUrl.split("\\*");
        title = URLDecoder.decode(title, "utf-8");
        for(int i = 0; i < puzzle_count_copies_scheme.length; i++) {
            puzzle_count_copies_scheme[i] = URLDecoder.decode(puzzle_count_copies_scheme[i], "utf-8");
        }
        String countStr = "";
        String copiesStr = "";
        String scheme = "";
        String puzzle;
        switch(puzzle_count_copies_scheme.length) {
            case 4:
                scheme = puzzle_count_copies_scheme[3];
            case 3:
                copiesStr = puzzle_count_copies_scheme[2];
            case 2:
                countStr = puzzle_count_copies_scheme[1];
            case 1:
                puzzle = puzzle_count_copies_scheme[0];
                break;
            default:
                throw new InvalidScrambleRequestException("Invalid puzzle request " + scrambleRequestUrl);
        }

        LazyInstantiator<Puzzle> lazyScrambler = puzzles.get(puzzle);
        if(lazyScrambler == null) {
            throw new InvalidScrambleRequestException("Invalid scrambler: " + puzzle);
        }

        try {
            this.scrambler = lazyScrambler.cachedInstance();
        } catch (Exception e) {
            throw new InvalidScrambleRequestException(e);
        }

        ScrambleCacher scrambleCacher = scrambleCachers.get(puzzle);
        if(scrambleCacher == null) {
            scrambleCacher = new ScrambleCacher(scrambler);
            scrambleCachers.put(puzzle, scrambleCacher);
        }

        this.title = title;
        fmc = countStr.equals("fmc");
        int count;
        if(fmc) {
            count = 1;
        } else {
            count = Math.min(toInt(countStr, 1), MAX_COUNT);
        }
        this.copies = Math.min(toInt(copiesStr, 1), MAX_COPIES);
        if(seed != null) {
            this.scrambles = scrambler.generateSeededScrambles(seed, count);
        } else {
            this.scrambles = scrambleCacher.newScrambles(count);
        }

        this.colorScheme = scrambler.parseColorScheme(scheme);
    }


    public List<String> getAllScrambles() {
        ArrayList<String> allScrambles = new ArrayList<String>(Arrays.asList(scrambles));
        if(extraScrambles != null) {
            allScrambles.addAll(Arrays.asList(extraScrambles));
        }
        return allScrambles;
    }


    public static ScrambleRequest[] parseScrambleRequests(Map<String, String> query, String seed) throws UnsupportedEncodingException, InvalidScrambleRequestException {
        ScrambleRequest[] scrambleRequests;
        if(query.size() == 0) {
            throw new InvalidScrambleRequestException("Must specify at least one scramble request");
        } else {
            scrambleRequests = new ScrambleRequest[query.size()];
            int i = 0;
            for(String title : query.keySet()) {
                // Note that we prefix the seed with the title of the round! This ensures that we get unique
                // scrambles in different rounds. Thanks to Ravi Fernando for noticing this at Stanford Fall 2011.
                // (http://www.worldcubeassociation.org/results/c.php?i=StanfordFall2011).
                String uniqueSeed = null;
                if(seed != null) {
                    uniqueSeed = title + seed;
                }
                scrambleRequests[i++] = new ScrambleRequest(title, query.get(title), uniqueSeed);
            }
        }
        return scrambleRequests;
    }

    private static ByteArrayOutputStream createPdf(String globalTitle, Date creationDate, ScrambleRequest scrambleRequest, Locale locale, String password) throws DocumentException, IOException {
        // 333mbf is handled pretty specially: each "scramble" is actually a newline separated
        // list of 333ni scrambles.
        // If we detect that we're dealing with 333mbf, then we will generate 1 sheet per attempt,
        // rather than 1 sheet per round (as we do with every other event).
        
        // for ordered scrambles, we recreate scrambleRequest so it contains only 1 scramble
        // to fix this, we pass the attempt number
        boolean is333mbf = scrambleRequest.event.equals("333mbf");
        if(is333mbf) {
            Document doc = new Document();
            ByteArrayOutputStream totalPdfOutput = new ByteArrayOutputStream();
            PdfSmartCopy totalPdfWriter = new PdfSmartCopy(doc, totalPdfOutput);
            if (password != null) {
                totalPdfWriter.setEncryption(password.getBytes(), password.getBytes(), PdfWriter.ALLOW_PRINTING, PdfWriter.STANDARD_ENCRYPTION_128);
            }
            doc.open();

            for(int nthAttempt = 1; nthAttempt <= scrambleRequest.scrambles.length; nthAttempt++) {
                String[] scrambles = scrambleRequest.scrambles[nthAttempt - 1].split("\n");

                ScrambleRequest attemptRequest = new ScrambleRequest();
                attemptRequest.scrambles = scrambles;
                attemptRequest.extraScrambles = new String[0];
                attemptRequest.scrambler = scrambleRequest.scrambler;
                attemptRequest.copies = scrambleRequest.copies;
                attemptRequest.title = scrambleRequest.title + " Attempt " + (scrambleRequest.attempt>1? scrambleRequest.attempt: nthAttempt);
                attemptRequest.fmc = false;
                attemptRequest.event = "333bf";
                attemptRequest.colorScheme = scrambleRequest.colorScheme;

                // We pass a null password, since the resulting pages will be processed further before encryption.
                PdfReader pdfReader = new PdfReader(createPdf(globalTitle, creationDate, attemptRequest, locale, null).toByteArray());
                for(int pageN = 1; pageN <= pdfReader.getNumberOfPages(); pageN++) {
                    PdfImportedPage page = totalPdfWriter.getImportedPage(pdfReader, pageN);
                    totalPdfWriter.addPage(page);
                }
            }
            doc.close();
            return totalPdfOutput;
        }

        azzert(scrambleRequest.scrambles.length > 0);
        ByteArrayOutputStream pdfOut = new ByteArrayOutputStream();
        Rectangle pageSize = PageSize.LETTER;
        Document doc = new Document(pageSize, 0, 0, 75, 75);
        PdfWriter docWriter = PdfWriter.getInstance(doc, pdfOut);
        if(scrambleRequest.fmc && password != null) {
            // We don't watermark the FMC sheets because they already have
            // the competition name on them. So we encrypt directly.
            docWriter.setEncryption(password.getBytes(), password.getBytes(), PdfWriter.ALLOW_PRINTING, PdfWriter.STANDARD_ENCRYPTION_128);
        }

        docWriter.setBoxSize("art", new Rectangle(36, 54, pageSize.getWidth()-36, pageSize.getHeight()-54));

        doc.addCreationDate();
        doc.addProducer();
        if(globalTitle != null) {
            doc.addTitle(globalTitle);
        }

        doc.open();
        // Note that we ignore scrambleRequest.copies here.
        addScrambles(docWriter, doc, scrambleRequest, globalTitle, locale);
        doc.close();

        if(scrambleRequest.fmc) {
            // We don't watermark the FMC sheets because they already have
            // the competition name on them.
            return pdfOut;
        }
        // TODO - is there a better way to convert from a PdfWriter to a PdfReader?
        PdfReader pr = new PdfReader(pdfOut.toByteArray());

        pdfOut = new ByteArrayOutputStream();
        doc = new Document(pageSize, 0, 0, 75, 75);
        docWriter = PdfWriter.getInstance(doc, pdfOut);
        if (password != null) {
            docWriter.setEncryption(password.getBytes(), password.getBytes(), PdfWriter.ALLOW_PRINTING, PdfWriter.STANDARD_ENCRYPTION_128);
        }
        doc.open();

        PdfContentByte cb = docWriter.getDirectContent();

        for(int pageN = 1; pageN <= pr.getNumberOfPages(); pageN++) {
            PdfImportedPage page = docWriter.getImportedPage(pr, pageN);

            doc.newPage();
            cb.addTemplate(page, 0, 0);

            Rectangle rect = pr.getBoxSize(pageN, "art");

            // Header
            ColumnText.showTextAligned(cb,
                    Element.ALIGN_LEFT, new Phrase(Utils.SDF.format(creationDate)),
                    rect.getLeft(), rect.getTop(), 0);

            ColumnText.showTextAligned(cb,
                    Element.ALIGN_CENTER, new Phrase(globalTitle),
                    (pageSize.getLeft() + pageSize.getRight()) / 2, pageSize.getTop() - 60, 0);

            ColumnText.showTextAligned(cb,
                    Element.ALIGN_CENTER, new Phrase(scrambleRequest.title),
                    (pageSize.getLeft() + pageSize.getRight()) / 2, pageSize.getTop() - 45, 0);

            if(pr.getNumberOfPages() > 1) {
                ColumnText.showTextAligned(cb,
                        Element.ALIGN_RIGHT, new Phrase(pageN + "/" + pr.getNumberOfPages()),
                        rect.getRight(), rect.getTop(), 0);
            }

            // Footer
            String generatedBy = "Generated by " + Utils.getProjectName() + "-" + Utils.getVersion();
            ColumnText.showTextAligned(cb,
                    Element.ALIGN_CENTER, new Phrase(generatedBy),
                    (pageSize.getLeft() + pageSize.getRight()) / 2, pageSize.getBottom() + 40, 0);
        }

        doc.close();

        // TODO - is there a better way to convert from a PdfWriter to a PdfReader?
        return pdfOut;

//      The PdfStamper class doesn't seem to be working.
//      pdfOut = new ByteArrayOutputStream();
//      PdfStamper ps = new PdfStamper(pr, pdfOut);
//
//      for(int pageN = 1; pageN <= pr.getNumberOfPages(); pageN++) {
//          PdfContentByte pb = ps.getUnderContent(pageN);
//          Rectangle rect = pr.getBoxSize(pageN, "art");
//          System.out.println(rect.getLeft());
//          System.out.println(rect.getWidth());
//          ColumnText.showTextAligned(pb,
//                  Element.ALIGN_LEFT, new Phrase("Hello people!"), 36, 540, 0);
////            ColumnText.showTextAligned(pb,
////                    Element.ALIGN_CENTER, new Phrase("HELLO WORLD"),
////                    (rect.getLeft() + rect.getRight()) / 2, rect.getTop(), 0);
//      }
//      ps.close();
//      return ps.getReader();
    }

    private static void addScrambles(PdfWriter docWriter, Document doc, ScrambleRequest scrambleRequest, String globalTitle, Locale locale) throws DocumentException, IOException {
        if(scrambleRequest.fmc) {
            for(int i = 0; i < scrambleRequest.scrambles.length; i++) {
                addFmcSolutionSheet(docWriter, doc, scrambleRequest, globalTitle, i, locale);
            }
        } else {
            Rectangle pageSize = doc.getPageSize();

            float sideMargins = 100 + doc.leftMargin() + doc.rightMargin();
            float availableWidth = pageSize.getWidth()-sideMargins;
            float vertMargins = doc.topMargin() + doc.bottomMargin();
            float availableHeight = pageSize.getHeight() - vertMargins;
            if(scrambleRequest.extraScrambles.length > 0) {
                availableHeight -= 20; // Yeee magic numbers. This should make space for the headerTable.
            }
            int scramblesPerPage = Math.min(MAX_SCRAMBLES_PER_PAGE, scrambleRequest.getAllScrambles().size());
            int maxScrambleImageHeight = (int) (availableHeight/scramblesPerPage - 2*SCRAMBLE_IMAGE_PADDING);

            int maxScrambleImageWidth = (int) (availableWidth/2); // We don't let scramble images take up more than half the page
            if(scrambleRequest.scrambler.getShortName().equals("minx")) {
                // TODO - If we allow the megaminx image to be too wide, the
                // megaminx scrambles get really tiny. This tweak allocates
                // a more optimal amount of space to the scrambles. This is possible
                // because the scrambles are so uniformly sized.
                maxScrambleImageWidth = 190;
            }

            Dimension scrambleImageSize = scrambleRequest.scrambler.getPreferredSize(maxScrambleImageWidth, maxScrambleImageHeight);

            // First do a dry run just to see if any scrambles require highlighting.
            // Then do the real run, and force highlighting on every scramble
            // if any scramble required it.
            boolean forceHighlighting = false;
            for(boolean dryRun : new boolean[]{ true, false }) {
                String scrambleNumberPrefix = "";
                TableAndHighlighting tableAndHighlighting = createTable(docWriter, doc, sideMargins, scrambleImageSize, scrambleRequest.scrambles, scrambleRequest.scrambler, scrambleRequest.colorScheme, scrambleNumberPrefix, forceHighlighting);
                if(dryRun) {
                    if(tableAndHighlighting.highlighting) {
                        forceHighlighting = true;
                        continue;
                    }
                } else {
                    doc.add(tableAndHighlighting.table);
                }

                if(scrambleRequest.extraScrambles.length > 0) {
                    PdfPTable headerTable = new PdfPTable(1);
                    headerTable.setTotalWidth(new float[] { availableWidth });
                    headerTable.setLockedWidth(true);

                    PdfPCell extraScramblesHeader = new PdfPCell(new Paragraph("Extra scrambles"));
                    extraScramblesHeader.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
                    extraScramblesHeader.setPaddingBottom(3);
                    headerTable.addCell(extraScramblesHeader);
                    if(!dryRun) {
                        doc.add(headerTable);
                    }

                    scrambleNumberPrefix = "E";
                    TableAndHighlighting extraTableAndHighlighting = createTable(docWriter, doc, sideMargins, scrambleImageSize, scrambleRequest.extraScrambles, scrambleRequest.scrambler, scrambleRequest.colorScheme, scrambleNumberPrefix, forceHighlighting);
                    if(dryRun) {
                        if(tableAndHighlighting.highlighting) {
                            forceHighlighting = true;
                            continue;
                        }
                    } else {
                        doc.add(extraTableAndHighlighting.table);
                    }
                }
            }
        }
        doc.newPage();
    }

    private static void addFmcSolutionSheet(PdfWriter docWriter, Document doc, ScrambleRequest scrambleRequest, String globalTitle, int index, Locale locale) throws DocumentException, IOException {
        boolean withScramble = index != -1;
        Rectangle pageSize = doc.getPageSize();
        String scramble = null;
        if(withScramble) {
            scramble = scrambleRequest.scrambles[index];
        }
        PdfContentByte cb = docWriter.getDirectContent();
        float LINE_THICKNESS = 0.5f;
        BaseFont bf = getFontForLocale(locale);

        int bottom = 30;
        int left = 35;
        int right = (int) (pageSize.getWidth()-left);
        int top = (int) (pageSize.getHeight()-bottom);

        int height = top - bottom;
        int width = right - left;

        int solutionBorderTop = bottom + (int) (height*.5);
        int scrambleBorderTop = solutionBorderTop + 40;

        int competitorInfoBottom = top - (int) (height*(withScramble ? .15 : .27));
        int gradeBottom = competitorInfoBottom - 50;
        int competitorInfoLeft = right - (int) (width*.45);

        int rulesRight = competitorInfoLeft;

        int padding = 5;

        // Outer border
        cb.setLineWidth(2f);
        cb.moveTo(left, top);
        cb.lineTo(left, bottom);
        cb.lineTo(right, bottom);
        cb.lineTo(right, top);

        // Solution border
        if(withScramble) {
            cb.moveTo(left, solutionBorderTop);
            cb.lineTo(right, solutionBorderTop);
        }

        // Rules bottom border
        cb.moveTo(left, scrambleBorderTop);
        cb.lineTo((withScramble ? rulesRight : right), scrambleBorderTop);

        // Rules right border
        if(!withScramble) {
            cb.moveTo(rulesRight, scrambleBorderTop);
        }
        cb.lineTo(rulesRight, gradeBottom);

        // Grade bottom border
        cb.moveTo(competitorInfoLeft, gradeBottom);
        cb.lineTo(right, gradeBottom);

        // Competitor info bottom border
        cb.moveTo(competitorInfoLeft, competitorInfoBottom);
        cb.lineTo(right, competitorInfoBottom);

        // Competitor info left border
        cb.moveTo(competitorInfoLeft, gradeBottom);
        cb.lineTo(competitorInfoLeft, top);

        // Solution lines
        int availableSolutionWidth = right - left;
        int availableSolutionHeight = scrambleBorderTop - bottom;
        int lineWidth = 25;
        int linesX = 10;
        int linesY = (int) Math.ceil(1.0*WCA_MAX_MOVES_FMC / linesX);

        cb.setLineWidth(LINE_THICKNESS);
        cb.stroke();

        int excessX = availableSolutionWidth-linesX*lineWidth;
        int moveCount = 0;
    solutionLines:
        for(int y = 0; y < linesY; y++) {
            for(int x = 0; x < linesX; x++) {
                if(moveCount >= WCA_MAX_MOVES_FMC) {
                    break solutionLines;
                }
                int xPos = left + x*lineWidth + (x+1)*excessX/(linesX+1);
                int yPos = (withScramble ? solutionBorderTop : scrambleBorderTop) - (y+1)*availableSolutionHeight/(linesY+1);
                cb.moveTo(xPos, yPos);
                cb.lineTo(xPos+lineWidth, yPos);
                moveCount++;
            }
        }

        float UNDERLINE_THICKNESS = 0.2f;
        cb.setLineWidth(UNDERLINE_THICKNESS);
        cb.stroke();

        if(withScramble) {
            cb.beginText();
            int availableScrambleSpace = right-left - 2*padding;
            int scrambleFontSize = 20;
            String scrambleStr = translate("fmc.scramble", locale) + ": " + scramble;
            float scrambleWidth;
            do {
                scrambleFontSize--;
                scrambleWidth = bf.getWidthPoint(scrambleStr, scrambleFontSize);
            } while(scrambleWidth > availableScrambleSpace);

            cb.setFontAndSize(bf, scrambleFontSize);
            int scrambleY = 3 + solutionBorderTop+(scrambleBorderTop-solutionBorderTop-scrambleFontSize)/2;
            cb.showTextAligned(PdfContentByte.ALIGN_LEFT, scrambleStr, left+padding, scrambleY, 0);
            cb.endText();

            int availableScrambleWidth = right-rulesRight;
            int availableScrambleHeight = gradeBottom-scrambleBorderTop;
            Dimension dim = scrambleRequest.scrambler.getPreferredSize(availableScrambleWidth-2, availableScrambleHeight-2);
            PdfTemplate tp = cb.createTemplate(dim.width, dim.height);
            Graphics2D g2 = new PdfGraphics2D(tp, dim.width, dim.height, new DefaultFontMapper());

            try {
                Svg svg = scrambleRequest.scrambler.drawScramble(scramble, scrambleRequest.colorScheme);
                drawSvgToGraphics2D(svg, g2, dim);
            } catch (InvalidScrambleException e) {
                l.log(Level.INFO, "", e);
            } finally {
                g2.dispose();
            }


            cb.addImage(Image.getInstance(tp), dim.width, 0, 0, dim.height, rulesRight + (availableScrambleWidth-dim.width)/2, scrambleBorderTop + (availableScrambleHeight-dim.height)/2);
        }

        int fontSize = 15;
        int margin = 5;
        boolean showScrambleCount = withScramble && (scrambleRequest.scrambles.length > 1 || scrambleRequest.totalAttempt > 1);

        Rectangle competitorInfoRect = new Rectangle(competitorInfoLeft+margin, top, right-margin, competitorInfoBottom);
        Rectangle gradeRect = new Rectangle(competitorInfoLeft+margin, competitorInfoBottom, right-margin, gradeBottom);
        Rectangle scrambleImageRect = new Rectangle(competitorInfoLeft+margin, gradeBottom, right-margin, scrambleBorderTop);

        String shortFill = ": ____";
        String longFill = ": __________________";

        // competitor and competition info
        ArrayList<String> list = new ArrayList<String>();
        ArrayList<Integer> alignList = new ArrayList<Integer>();

        if(withScramble) {
            list.add(globalTitle);
            alignList.add(Element.ALIGN_CENTER);
            list.add(scrambleRequest.title);
            alignList.add(Element.ALIGN_CENTER);

            if(showScrambleCount) {
                
                if (scrambleRequest.totalAttempt > 1) { // this is for ordered scrambles
                    index = Math.max(scrambleRequest.attempt-1, index);
                } else {
                    scrambleRequest.totalAttempt = scrambleRequest.scrambles.length;
                }

                HashMap<String, String> substitutions = new HashMap<String, String>();
                substitutions.put("scrambleIndex", ""+(index+1));
                substitutions.put("scrambleCount", ""+(scrambleRequest.totalAttempt));
                list.add(translate("fmc.scrambleXofY", locale, substitutions));
                alignList.add(Element.ALIGN_CENTER);
            }
        } else {
            list.add(translate("fmc.competition", locale)+longFill);
            alignList.add(Element.ALIGN_LEFT);
            list.add(translate("fmc.round", locale)+shortFill);
            alignList.add(Element.ALIGN_LEFT);
            list.add(translate("fmc.attempt", locale)+shortFill);
            alignList.add(Element.ALIGN_LEFT);
        }
        if (withScramble) { // more space for filling name
            list.add("");
            alignList.add(Element.ALIGN_LEFT);
        }
        list.add(translate("fmc.competitor", locale)+longFill);
        alignList.add(Element.ALIGN_LEFT);
        if (withScramble) {
            list.add("");
            alignList.add(Element.ALIGN_LEFT);
        }
        list.add("WCA ID: __ __ __ __  __ __ __ __  __ __");
        alignList.add(Element.ALIGN_LEFT);
        if (withScramble) { // add space below
            list.add("");
            alignList.add(Element.ALIGN_LEFT);
        }
        list.add(translate("fmc.registrantId", locale)+shortFill);
        alignList.add(Element.ALIGN_LEFT);
        if (withScramble) {
            list.add("");
            alignList.add(Element.ALIGN_LEFT);
        }
        populateRect(cb, competitorInfoRect, list, alignList, bf, fontSize);

        // graded
        fontSize = 11;
        list = new ArrayList<String>();
        alignList = new ArrayList<Integer>();
        list.add(translate("fmc.warning", locale));
        alignList.add(Element.ALIGN_CENTER);
        list.add(translate("fmc.graded", locale)+longFill+" "+translate("fmc.result", locale)+shortFill);
        alignList.add(Element.ALIGN_CENTER);
        fontSize = 11;
        populateRect(cb, gradeRect, list, alignList, bf, fontSize);

        if(!withScramble) {
            fontSize = 11;

            list = new ArrayList<String>();
            alignList = new ArrayList<Integer>();

            list.add(""); // fake vertical centering
            alignList.add(Element.ALIGN_CENTER);

            list.add(translate("fmc.scrambleOnSeparateSheet", locale));
            alignList.add(Element.ALIGN_CENTER);

            populateRect(cb, scrambleImageRect, list, alignList, bf, fontSize);
        }

        int fmcMargin = 10;

        // Table
        int tableWidth = competitorInfoLeft-left-2*fmcMargin;
        int tableHeight = 160;
        int tableLines = 8;
        int cellWidth = 25;
        int cellHeight = tableHeight/tableLines;
        int columns = 7;
        int firstColumnWidth = tableWidth-(columns-1)*cellWidth;

        int movesFontSize = 10;
        Font movesFont = new Font(bf, movesFontSize);

        PdfPTable table = new PdfPTable(columns);
        table.setTotalWidth(new float[]{firstColumnWidth, cellWidth, cellWidth, cellWidth, cellWidth, cellWidth, cellWidth});
        table.setLockedWidth(true);

        String[] movesType = {
                translate("fmc.faceMoves", locale),
                translate("fmc.rotations", locale)};
        String[] direction = {
                translate("fmc.clockwise", locale),
                translate("fmc.counterClockwise", locale),
                translate("fmc.double", locale)};

        String[] directionModifiers = {"", "'", "2"};
        String[] moves = {"F", "R", "U", "B", "L", "D"};
        String[][][] movesCell = new String[movesType.length][direction.length][moves.length];

        // Face moves.
        for (int i=0; i<directionModifiers.length; i++){
            for (int j=0; j<moves.length; j++){
                movesCell[0][i][j] = moves[j]+directionModifiers[i];
            }
        }
        // Rotations.
        for (int i=0; i<directionModifiers.length; i++){
            for (int j=0; j<moves.length; j++){
                movesCell[1][i][j] = "["+moves[j].toLowerCase()+directionModifiers[i]+"]";
            }
        }

        Rectangle firstColumnRectangle = new Rectangle(firstColumnWidth, cellHeight);
        float firstColumnFontSize = fitText(new Font(bf), movesType[0], firstColumnRectangle, 10, false, 1f);

        for (String item : movesType){
            firstColumnFontSize = Math.min(firstColumnFontSize, fitText(new Font(bf, firstColumnFontSize, Font.BOLD), item, firstColumnRectangle, 10, false, 1f));
        }
        for (String item : direction){
            firstColumnFontSize = Math.min(firstColumnFontSize, fitText(new Font(bf, firstColumnFontSize), item, firstColumnRectangle, 10, false, 1f));
        }

        // Center the table
        float maxFirstColumnWidth = 0;
        float maxLastColumnWidth = 0;

        for (int i=0; i<movesType.length; i++) {

            maxFirstColumnWidth = Math.max(maxFirstColumnWidth, bf.getWidthPoint(movesType[i], firstColumnFontSize));

            PdfPCell cell = new PdfPCell(new Phrase(movesType[i], new Font(bf, firstColumnFontSize, Font.BOLD)));
            cell.setFixedHeight(cellHeight);
            cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            cell.setBorder(Rectangle.NO_BORDER);
            table.addCell(cell);

            cell = new PdfPCell(new Phrase(""));
            cell.setFixedHeight(cellHeight);
            cell.setColspan(columns-1);
            cell.setBorder(Rectangle.NO_BORDER);
            table.addCell(cell);

            for (int j=0; j<directionModifiers.length; j++) {

                maxFirstColumnWidth = Math.max(maxFirstColumnWidth, bf.getWidthPoint(direction[j], firstColumnFontSize));

                cell = new PdfPCell(new Phrase(direction[j], new Font(bf, firstColumnFontSize)));
                cell.setFixedHeight(cellHeight);
                cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
                cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                cell.setBorder(Rectangle.NO_BORDER);
                table.addCell(cell);
                for (int k=0; k<moves.length; k++) {
                    cell = new PdfPCell(new Phrase(movesCell[i][j][k], movesFont));
                    cell.setFixedHeight(cellHeight);
                    cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
                    cell.setHorizontalAlignment(Element.ALIGN_CENTER);
                    cell.setBorder(Rectangle.NO_BORDER);
                    table.addCell(cell);

                    if (k == moves.length-1) {
                        maxLastColumnWidth = Math.max(maxLastColumnWidth, bf.getWidthPoint(movesCell[i][j][k], movesFontSize));
                    }
                }
            }
        }

        // Position the table
        table.writeSelectedRows(0, -1, left+fmcMargin+(cellWidth-maxLastColumnWidth)/2-(firstColumnWidth-maxFirstColumnWidth)/2, scrambleBorderTop+tableHeight+fmcMargin, cb);

        // Rules
        int MAGIC_NUMBER = 30; // kill me now
        float leadingMultiplier = 1;
        fontSize = 25;

        Rectangle rect = new Rectangle(left, top-MAGIC_NUMBER+fontSize, competitorInfoLeft, top-MAGIC_NUMBER);
        fitAndShowText(cb, translate("fmc.event", locale), bf, rect, fontSize, Element.ALIGN_CENTER, leadingMultiplier);

        ArrayList<String> rulesList = new ArrayList<String>();
        rulesList.add("• "+translate("fmc.rule1", locale));
        rulesList.add("• "+translate("fmc.rule2", locale));
        rulesList.add("• "+translate("fmc.rule3", locale));

        int maxMoves = WCA_MAX_MOVES_FMC;

        HashMap<String, String> substitutions = new HashMap<String, String>();
        substitutions.put("maxMoves", ""+maxMoves);
        rulesList.add("• "+translate("fmc.rule4", locale, substitutions));

        rulesList.add("• "+translate("fmc.rule5", locale));
        rulesList.add("• "+translate("fmc.rule6", locale));

        int rulesTop = competitorInfoBottom + (withScramble ? 65 : 153);

        leadingMultiplier = 1.5f;
        Rectangle rulesRectangle = new Rectangle(left+fmcMargin, scrambleBorderTop+tableHeight+fmcMargin, competitorInfoLeft-fmcMargin, rulesTop+fmcMargin);
        String rules = String.join("\n", rulesList);
        fitAndShowText(cb, rules, bf, rulesRectangle, 15, Element.ALIGN_JUSTIFIED, leadingMultiplier);

        doc.newPage();
    }

    private static void fitAndShowText(PdfContentByte cb, String text, BaseFont bf, Rectangle rect, float maxFontSize, int align, float leadingMultiplier) throws DocumentException {
        // We create a temp pdf and check if the text fit in a rectangle there.
        // If it's ok, we add the text to original pdf.

        do{
            PdfContentByte tempCb = new PdfContentByte(cb.getPdfWriter());

            ColumnText tempCt = new ColumnText(tempCb);
            tempCt.setSimpleColumn(rect);
            tempCt.setLeading(leadingMultiplier*maxFontSize);

            Paragraph p = new Paragraph(text, new Font(bf, maxFontSize));
            tempCt.addText(p);

            int status = tempCt.go();
            if (!ColumnText.hasMoreText(status)) { // all the text fit in the rectangle
                ColumnText ct = new ColumnText(cb);
                ct.setSimpleColumn(rect);
                ct.setAlignment(align);
                ct.setLeading(leadingMultiplier*maxFontSize);
                ct.addText(p);
                ct.go();
                break;
            }

            maxFontSize -= 0.1;
        } while(true);
    }

    private static void populateRect(PdfContentByte cb, Rectangle rect, ArrayList<String> list, ArrayList<Integer> alignList, BaseFont bf, int fontSize)  throws DocumentException {

        azzert(list.size() == alignList.size(), "Make sure list.size() == alignList.size()");

        float totalHeight = rect.getHeight();
        float width = rect.getWidth();
        float x = rect.getLeft();
        float y = rect.getTop();

        float height = totalHeight/list.size();

        for (int i=0; i<list.size(); i++) {
            Rectangle temp = new Rectangle(x, y+height*i-totalHeight-fontSize, x+width, y+height*i-totalHeight);
            fitAndShowText(cb, list.get(i), bf, temp, 15, alignList.get(i), 1f);
        }
    }

    private static void addGenericFmcSolutionSheet(PdfWriter docWriter, Document doc, String globalTitle, Locale locale) throws DocumentException, IOException {
        addFmcSolutionSheet(docWriter, doc, null, globalTitle, -1, locale);
    }

    private static void addFmcScrambleCutoutSheet(PdfWriter docWriter, Document doc, ScrambleRequest scrambleRequest, String globalTitle, int index) throws DocumentException, IOException {

        Rectangle pageSize = doc.getPageSize();
        String scramble = scrambleRequest.scrambles[index];
        PdfContentByte cb = docWriter.getDirectContent();

        BaseFont bf = getFontForLocale(Translate.DEFAULT_LOCALE);

        int bottom = 10;
        int left = 20;
        int right = (int) (pageSize.getWidth()-left);
        int top = (int) (pageSize.getHeight()-bottom);

        int height = top - bottom;
        int width = right - left;

        int spaceScrambleImage = 5; // scramble image won't touch the scramble
        int scrambleImagePadding = 8; // scramble image won't touch the dashed lines
        int fontSize = 20;
        
        final int scramblesPerSheet = 8;
        int availableScrambleHeight = height/scramblesPerSheet;

        int availableScrambleWidth = (int) (width * .45);
        Dimension dim = scrambleRequest.scrambler.getPreferredSize(availableScrambleWidth, availableScrambleHeight - 2*scrambleImagePadding);
        PdfTemplate tp = cb.createTemplate(dim.width, dim.height);
        Graphics2D g2 = new PdfGraphics2D(tp, dim.width, dim.height, new DefaultFontMapper());

        try {
            Svg svg = scrambleRequest.scrambler.drawScramble(scramble, scrambleRequest.colorScheme);
            drawSvgToGraphics2D(svg, g2, dim);
        } catch (InvalidScrambleException e) {
            l.log(Level.INFO, "", e);
        } finally {
            g2.dispose();
        }

        String title = globalTitle + " - " + scrambleRequest.title;
        if(scrambleRequest.scrambles.length > 1) {
            title += " - Scramble " + (index + 1) + " of " + scrambleRequest.scrambles.length;
        }
        
        ArrayList<String> list = new ArrayList<String>();
        ArrayList<Integer> alignList = new ArrayList<Integer>();
        
        list.add(""); // space above
        alignList.add(Element.ALIGN_LEFT);
        
        list.add(title);
        alignList.add(Element.ALIGN_LEFT);
        list.add(scramble);
        alignList.add(Element.ALIGN_LEFT);

        list.add(""); // space bellow
        alignList.add(Element.ALIGN_LEFT);

        for (int i = 0; i < scramblesPerSheet; i++) {
            Rectangle rect = new Rectangle(left, top - i*availableScrambleHeight, right - dim.width - spaceScrambleImage, top - (i+1)*availableScrambleHeight);
            populateRect(cb, rect, list, alignList, bf, fontSize);

            cb.addImage(Image.getInstance(tp), dim.width, 0, 0, dim.height, right - dim.width, top - (i+1)*availableScrambleHeight + (availableScrambleHeight-dim.getHeight())/2);

            drawDashedLine(cb, left, right, top - i*availableScrambleHeight);
        }
        drawDashedLine(cb, left, right, top - scramblesPerSheet*availableScrambleHeight);
        
        doc.newPage();
    }
    
    private static void drawDashedLine(PdfContentByte cb, int left, int right, int yPosition) {
        cb.setLineDash(3f, 3f);
        cb.moveTo(left, yPosition);
        cb.lineTo(right, yPosition);
        cb.stroke();
    }

    /**
     * Copied from ColumnText.java in the itextpdf 5.3.0 source code.
     * Added the newlinesAllowed argument.

     * Fits the text to some rectangle adjusting the font size as needed.
     * @param font the font to use
     * @param text the text
     * @param rect the rectangle where the text must fit
     * @param maxFontSize the maximum font size
     * @param newlinesAllowed output text can be split into lines
     * @param leadingMultiplier leading multiplier between lines
     * @return the calculated font size that makes the text fit
     */
    private static final float FITTEXT_FONTSIZE_PRECISION = 0.1f;
    private static float fitText(Font font, String text, Rectangle rect, float maxFontSize, boolean newlinesAllowed, float leadingMultiplier) {

        // ideally, we could pass the object in which our text is going to be rendered
        // as argument instead of asking leadingMultiplier, but we are currently rendering
        // text in pdfcell, columntext and others
        // it'd be painful to render lines in a common object to ask leadingMultiplier

        float minFontSize = 1f;
        float potentialFontSize;

        while(true) {
            potentialFontSize = (maxFontSize + minFontSize) / 2.0f;
            font.setSize(potentialFontSize);

            LinkedList<Chunk> lineChunks = splitTextToLineChunks(text, font, rect.getWidth());
            if(!newlinesAllowed && lineChunks.size() > 1) {
                // If newlines are not allowed, and we had to split the text into more than
                // one line, then potentialFontSize is too large.
                maxFontSize = potentialFontSize;
            } else {
                // The font size seems to be a pretty good estimate for how
                // much vertical space a row actually takes up.

                float totalHeight = lineChunks.size() * potentialFontSize * leadingMultiplier;

                if(totalHeight < rect.getHeight()) {
                    minFontSize = potentialFontSize;
                } else {
                    maxFontSize = potentialFontSize;
                }
            }
            if(maxFontSize - minFontSize < FITTEXT_FONTSIZE_PRECISION) {
                // Err on the side of too small, because being too large will screw up
                // layout.
                potentialFontSize = minFontSize;
                break;
            }
        }
        return potentialFontSize;
    }

    private static LinkedList<Chunk> splitTextToLineChunks(String text, Font font, float textColumnWidth) {
        float availableTextWidth = textColumnWidth - 2*TEXT_PADDING_HORIZONTAL;

        LinkedList<Chunk> lineChunks = new LinkedList<Chunk>();
        String[] lineList = text.split("\n");

        for (String line:lineList) {
            int startIndex = 0;
            int endIndex = 0;
            while(startIndex < line.length()) {
                // Walk forwards until we've grabbed the maximum number of characters
                // that fit in a line or we've run out of characters.
                float substringWidth;
                for(endIndex++; endIndex <= line.length(); endIndex++) {
                    String substring = NON_BREAKING_SPACE + line.substring(startIndex, endIndex) + NON_BREAKING_SPACE;
                    substringWidth = font.getBaseFont().getWidthPoint(substring, font.getSize());
                    if(substringWidth > availableTextWidth) {
                        break;
                    }
                }
                // endIndex is one past the best fit, so remove one character and it should fit!
                endIndex--;

                // If we're not at the end of the text, make sure we're not cutting
                // a word (or turn) in half by walking backwards until we're right before a turn.
                // Any spaces added for padding after a turn are considered part of
                // that turn because they're actually NON_BREAKING_SPACE, not a ' '.
                int perfectFitEndIndex = endIndex;
                if(endIndex < line.length()) {
                    while(true) {
                        if(endIndex < startIndex) {
                            // We walked all the way to the beginning of the line
                            // without finding a good breaking point. Give up and break
                            // in the middle of a word =(.
                            endIndex = perfectFitEndIndex;
                            break;
                        }

                        // Another dirty hack for sq1: turns only line up
                        // nicely if every line starts with a (x,y). We ensure this
                        // by forcing every line to end with a /.
                        boolean isSquareOne = line.indexOf('/') >= 0;
                        if(isSquareOne) {
                            char previousCharacter = line.charAt(endIndex - 1);
                            if(previousCharacter == '/') {
                                break;
                            }
                        } else {
                            char currentCharacter = line.charAt(endIndex);
                            boolean isTurnCharacter = currentCharacter != ' ';
                            if(!isTurnCharacter) {
                                break;
                            }
                        }
                        endIndex--;
                    }
                }

                String substring = NON_BREAKING_SPACE + line.substring(startIndex, endIndex) + NON_BREAKING_SPACE;

                // Add NON_BREAKING_SPACE until the substring takes up as much as
                // space as is available on a line.
                do {
                    substring += NON_BREAKING_SPACE;
                    substringWidth = font.getBaseFont().getWidthPoint(substring, font.getSize());
                } while(substringWidth <= availableTextWidth);
                // substring is now too big for our line, so remove the
                // last character.
                substring = substring.substring(0, substring.length() - 1);

                // Walk past all whitespace that comes immediately after the line wrap
                // we are about to insert.
                while(endIndex < line.length() && (line.charAt(endIndex) == ' ')) {
                    endIndex++;
                }
                startIndex = endIndex;
                Chunk lineChunk = new Chunk(substring);
                lineChunks.add(lineChunk);
                lineChunk.setFont(font);

                // Force a line wrap!
                lineChunk.append("\n");
            }
        }

        return lineChunks;
    }

    static class TableAndHighlighting {
        PdfPTable table;
        boolean highlighting;
    }

    private static TableAndHighlighting createTable(PdfWriter docWriter, Document doc, float sideMargins, Dimension scrambleImageSize, String[] scrambles, Puzzle scrambler, HashMap<String, Color> colorScheme, String scrambleNumberPrefix, boolean forceHighlighting) throws DocumentException {
        PdfContentByte cb = docWriter.getDirectContent();

        PdfPTable table = new PdfPTable(3);

        float leadingMultiplier = 1;

        int charsWide = scrambleNumberPrefix.length() + 1 + (int) Math.log10(scrambles.length);
        String wideString = "";
        for(int i = 0; i < charsWide; i++) {
            // M has got to be as wide or wider than the widest digit in our font
            wideString += "M";
        }
        wideString += ".";
        float col1Width = new Chunk(wideString).getWidthPoint();
        // I don't know why we need this, perhaps there's some padding?
        col1Width += 5;

        float availableWidth = doc.getPageSize().getWidth() - sideMargins;
        float scrambleColumnWidth = availableWidth - col1Width - scrambleImageSize.width - 2*SCRAMBLE_IMAGE_PADDING;
        int availableScrambleHeight = scrambleImageSize.height - 2*SCRAMBLE_IMAGE_PADDING;

        table.setTotalWidth(new float[] { col1Width, scrambleColumnWidth, scrambleImageSize.width + 2*SCRAMBLE_IMAGE_PADDING });
        table.setLockedWidth(true);

        String longestScramble = "";
        String longestPaddedScramble = "";
        for(String scramble : scrambles) {
            if(scramble.length() > longestScramble.length()) {
                longestScramble = scramble;
            }

            String paddedScramble = padTurnsUniformly(scramble, "M");
            if(paddedScramble.length() > longestPaddedScramble.length()) {
                longestPaddedScramble = paddedScramble;
            }
        }
        // I don't know how to configure ColumnText.fitText's word wrapping characters,
        // so instead, I just replace each character I don't want to wrap with M, which
        // should be the widest character (we're using a monospaced font,
        // so that doesn't really matter), and won't get wrapped.
        char widestCharacter = 'M';
        longestPaddedScramble = longestPaddedScramble.replaceAll("\\S", widestCharacter + "");
        boolean tryToFitOnOneLine = true;
        if(longestPaddedScramble.indexOf("\n") >= 0) {
            // If the scramble contains newlines, then we *only* allow wrapping at the
            // newlines.
            longestPaddedScramble = longestPaddedScramble.replaceAll(" ", "M");
            tryToFitOnOneLine = false;
        }
        boolean oneLine = false;
        Font scrambleFont = null;

        Rectangle availableArea = new Rectangle(scrambleColumnWidth - 2*SCRAMBLE_PADDING_HORIZONTAL,
                availableScrambleHeight - SCRAMBLE_PADDING_VERTICAL_TOP - SCRAMBLE_PADDING_VERTICAL_BOTTOM);
        float perfectFontSize = fitText(new Font(monoFont), longestPaddedScramble, availableArea, MAX_SCRAMBLE_FONT_SIZE, true, leadingMultiplier);
        if(tryToFitOnOneLine) {
            String longestScrambleOneLine = longestScramble.replaceAll(".", widestCharacter + "");
            float perfectFontSizeForOneLine = fitText(new Font(monoFont), longestScrambleOneLine, availableArea, MAX_SCRAMBLE_FONT_SIZE, false, leadingMultiplier);
            oneLine = perfectFontSizeForOneLine >= MINIMUM_ONE_LINE_FONT_SIZE;
            if(oneLine) {
                perfectFontSize = perfectFontSizeForOneLine;
            }
        }
        scrambleFont = new Font(monoFont, perfectFontSize, Font.NORMAL);

        boolean highlight = forceHighlighting;
        for(int i = 0; i < scrambles.length; i++) {
            String scramble = scrambles[i];
            String paddedScramble = oneLine ? scramble : padTurnsUniformly(scramble, NON_BREAKING_SPACE + "");
            Chunk ch = new Chunk(scrambleNumberPrefix + (i+1) + ".");
            PdfPCell nthscramble = new PdfPCell(new Paragraph(ch));
            nthscramble.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
            table.addCell(nthscramble);

            Phrase scramblePhrase = new Phrase();
            int nthLine = 1;
            LinkedList<Chunk> lineChunks = splitTextToLineChunks(paddedScramble, scrambleFont, scrambleColumnWidth);
            if(lineChunks.size() >= MIN_LINES_TO_ALTERNATE_HIGHLIGHTING) {
                highlight = true;
            }

            for(Chunk lineChunk : lineChunks) {
                if(highlight && (nthLine % 2 == 0)) {
                    lineChunk.setBackground(HIGHLIGHT_COLOR);
                }
                scramblePhrase.add(lineChunk);
                nthLine++;
            }

            PdfPCell scrambleCell = new PdfPCell(new Paragraph(scramblePhrase));
            // We carefully inserted newlines ourselves to make stuff fit, don't
            // let itextpdf wrap lines for us.
            scrambleCell.setNoWrap(true);
            scrambleCell.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
            // This shifts everything up a little bit, because I don't like how
            // ALIGN_MIDDLE works.
            scrambleCell.setPaddingTop(-SCRAMBLE_PADDING_VERTICAL_TOP);
            scrambleCell.setPaddingBottom(SCRAMBLE_PADDING_VERTICAL_BOTTOM);
            scrambleCell.setPaddingLeft(SCRAMBLE_PADDING_HORIZONTAL);
            scrambleCell.setPaddingRight(SCRAMBLE_PADDING_HORIZONTAL);
            // We space lines a little bit more here - it still fits in the cell height
            leadingMultiplier = 1.1f;
            scrambleCell.setLeading(0, leadingMultiplier);
            table.addCell(scrambleCell);

            if(scrambleImageSize.width > 0 && scrambleImageSize.height > 0) {
                PdfTemplate tp = cb.createTemplate(scrambleImageSize.width + 2*SCRAMBLE_IMAGE_PADDING, scrambleImageSize.height + 2*SCRAMBLE_IMAGE_PADDING);
                Graphics2D g2 = new PdfGraphics2D(tp, tp.getWidth(), tp.getHeight(), new DefaultFontMapper());
                g2.translate(SCRAMBLE_IMAGE_PADDING, SCRAMBLE_IMAGE_PADDING);

                try {
                    Svg svg = scrambler.drawScramble(scramble, colorScheme);
                    drawSvgToGraphics2D(svg, g2, scrambleImageSize);
                } catch(Exception e) {
                    table.addCell("Error drawing scramble: " + e.getMessage());
                    l.log(Level.WARNING, "Error drawing scramble, if you're having font issues, try installing ttf-dejavu.", e);
                    continue;
                } finally {
                    g2.dispose(); // iTextPdf blows up if we do not dispose of this
                }
                PdfPCell imgCell = new PdfPCell(Image.getInstance(tp), true);
                imgCell.setBackgroundColor(BaseColor.LIGHT_GRAY);
                imgCell.setVerticalAlignment(PdfPCell.ALIGN_MIDDLE);
                imgCell.setHorizontalAlignment(PdfPCell.ALIGN_MIDDLE);
                table.addCell(imgCell);
            } else {
                table.addCell("");
            }
        }

        TableAndHighlighting tableAndHighlighting = new TableAndHighlighting();
        tableAndHighlighting.table = table;
        tableAndHighlighting.highlighting = highlight;
        return tableAndHighlighting;
    }

    private static void drawSvgToGraphics2D(Svg svg, Graphics2D g2, Dimension size) throws IOException {
        // Copied (and modified) from http://stackoverflow.com/a/12502943

        String parser = XMLResourceDescriptor.getXMLParserClassName();
        SAXSVGDocumentFactory factory = new SAXSVGDocumentFactory(parser);
        UserAgent userAgent = new UserAgentAdapter();
        DocumentLoader loader = new DocumentLoader(userAgent);
        BridgeContext ctx = new BridgeContext(userAgent, loader);
        ctx.setDynamicState(BridgeContext.DYNAMIC);
        GVTBuilder builder = new GVTBuilder();


        StringReader svgReader = new StringReader(svg.toString());
        SVGDocument parsedSvgDocument = factory.createSVGDocument(null, svgReader);
        GraphicsNode chartGfx = builder.build(ctx, parsedSvgDocument);
        Dimension actualSize = svg.getSize();
        double scaleWidth = 1.0*size.width / actualSize.width;
        double scaleHeight = 1.0*size.height / actualSize.height;
        chartGfx.setTransform(AffineTransform.getScaleInstance(scaleWidth, scaleHeight));

        chartGfx.paint(g2);
    }

    private static String padTurnsUniformly(String scramble, String padding) {
        azzert(scramble != null, "scramble cannot be null");
        String[] turns = scramble.split("\\s+");
        int maxTurnLength = 0;
        for(String turn : turns) {
            maxTurnLength = Math.max(maxTurnLength, turn.length());
        }

        StringBuilder s = new StringBuilder();

        String[] lines = scramble.split("\\n");
        for(int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if(i > 0) {
                s.append("\n");
            }
            turns = line.split("\\s+");
            for(int j = 0; j < turns.length; j++) {
                String turn = turns[j];
                if(j > 0) {
                    s.append(" ");
                }

                // TODO - this is a disgusting hack for sq1. We don't pad the /
                // turns because they're guaranteed to occur as every other turn,
                // so stuff will line up nicely without padding them. I don't know
                // what a good general solution to this problem is.
                if(!turn.equals("/")) {
                    while(turn.length() < maxTurnLength) {
                        turn += padding;
                    }
                }
                s.append(turn);
            }
        }

        return s.toString();
    }

    private static ArrayList<String> stripNewlines(List<String> strings) {
        ArrayList<String> newStrings = new ArrayList<String>();
        for(String newString : strings) {
            newStrings.add(newString.replaceAll("\n", " "));
        }
        return newStrings;
    }

    // Excludes ambiguous characters: 0/O, 1/I
    private static final String PASSCODE_DIGIT_SET = "23456789abcdefghijkmnpqrstuvwxyz";
    private static final int PASSCODE_NUM_CHARS = 8;

    private static String randomPasscode() {
        SecureRandom secureRandom = new SecureRandom();
        StringBuilder builder = new StringBuilder();
        for(int i = 0; i < PASSCODE_NUM_CHARS; i++) {
            int idx = secureRandom.nextInt(PASSCODE_DIGIT_SET.length());
            builder.append(PASSCODE_DIGIT_SET.charAt(idx));
        }
        return builder.toString();
    }

    public static ByteArrayOutputStream requestsToZip(ServletContext context, String globalTitle, Date generationDate, ScrambleRequest[] scrambleRequests, String password, String generationUrl, WCIFHelper wcifHelper) throws IOException, DocumentException, ZipException {
        ByteArrayOutputStream baosZip = new ByteArrayOutputStream();

        ZipParameters parameters = new ZipParameters();
        parameters.setCompressionMethod(Zip4jConstants.COMP_DEFLATE);
        parameters.setCompressionLevel(Zip4jConstants.DEFLATE_LEVEL_NORMAL);
        if(password != null) {
            parameters.setEncryptFiles(true);
            parameters.setEncryptionMethod(Zip4jConstants.ENC_METHOD_STANDARD);
            parameters.setPassword(password);
        }
        parameters.setSourceExternalStream(true);

        ZipOutputStream zipOut = new ZipOutputStream(baosZip);
        HashMap<String, Boolean> seenTitles = new HashMap<String, Boolean>();

        // Computer display zip
        // This .zip file is nested in the main .zip. It is intentionally not
        // protected with a password, since it's just an easy way to distribute
        // a collection of files that are each are encrypted using their own
        // passcode.
        ByteArrayOutputStream computerDisplayBaosZip = new ByteArrayOutputStream();
        ZipParameters computerDisplayZipParameters = new ZipParameters();
        computerDisplayZipParameters.setCompressionMethod(Zip4jConstants.COMP_DEFLATE);
        computerDisplayZipParameters.setCompressionLevel(Zip4jConstants.DEFLATE_LEVEL_NORMAL);
        computerDisplayZipParameters.setSourceExternalStream(true);
        ZipOutputStream computerDisplayZipOut = new ZipOutputStream(computerDisplayBaosZip);

        String safeGlobalTitle = toFileSafeString(globalTitle);
        String computerDisplayFileName = safeGlobalTitle + " - Computer Display PDFs";

        boolean fmcBeingHeld = false;
        for(ScrambleRequest scrambleRequest : scrambleRequests) {
            if(scrambleRequest.fmc) {
                fmcBeingHeld = true;

                String safeTitle = toFileSafeString(scrambleRequest.title) + " - Scramble Cutout Sheet";
                int salt = 0;
                String tempNewSafeTitle = safeTitle;
                while(seenTitles.get(tempNewSafeTitle) != null) {
                    tempNewSafeTitle = safeTitle + " (" + (++salt) + ")";
                }
                safeTitle = tempNewSafeTitle;
                seenTitles.put(safeTitle, true);

                String pdfFileName = "Printing/Fewest Moves - Additional Files/" + safeTitle + ".pdf";
                parameters.setFileNameInZip(pdfFileName);
                zipOut.putNextEntry(null, parameters);

                ByteArrayOutputStream pdfOut = new ByteArrayOutputStream();
                Rectangle pageSize = PageSize.LETTER;
                Document doc = new Document(pageSize, 0, 0, 75, 75);
                PdfWriter docWriter = PdfWriter.getInstance(doc, pdfOut);

                docWriter.setBoxSize("art", new Rectangle(36, 54, pageSize.getWidth()-36, pageSize.getHeight()-54));

                doc.addCreationDate();
                doc.addProducer();
                if(globalTitle != null) {
                    doc.addTitle(globalTitle);
                }

                // TODO: i18n. See https://github.com/thewca/tnoodle/issues/396
                doc.open();
                for (int i = 0; i < scrambleRequest.scrambles.length; i++) {
                    addFmcScrambleCutoutSheet(docWriter, doc, scrambleRequest, globalTitle, i);
                }
                doc.close();

                PdfReader pdfReader = new PdfReader(pdfOut.toByteArray());
                byte[] b = new byte[(int) pdfReader.getFileLength()];
                pdfReader.getSafeFile().readFully(b);
                zipOut.write(b);

                zipOut.closeEntry();
            }
        }
        if(fmcBeingHeld) {
            String pdfFileName = "Printing/Fewest Moves - Additional Files/3x3x3 Fewest Moves Solution Sheet.pdf";
            parameters.setFileNameInZip(pdfFileName);
            zipOut.putNextEntry(null, parameters);

            ByteArrayOutputStream pdfOut = new ByteArrayOutputStream();
            Rectangle pageSize = PageSize.LETTER;
            Document doc = new Document(pageSize, 0, 0, 75, 75);
            PdfWriter docWriter = PdfWriter.getInstance(doc, pdfOut);

            docWriter.setBoxSize("art", new Rectangle(36, 54, pageSize.getWidth()-36, pageSize.getHeight()-54));

            doc.addCreationDate();
            doc.addProducer();
            if(globalTitle != null) {
                doc.addTitle(globalTitle);
            }

            doc.open();
            addGenericFmcSolutionSheet(docWriter, doc, globalTitle, Translate.DEFAULT_LOCALE);
            doc.close();

            // TODO - is there a better way to convert from a PdfWriter to a PdfReader?
            PdfReader pdfReader = new PdfReader(pdfOut.toByteArray());
            byte[] b = new byte[(int) pdfReader.getFileLength()];
            pdfReader.getSafeFile().readFully(b);
            zipOut.write(b);

            zipOut.closeEntry();
        }

        LinkedHashMap<String, String> passcodes = new LinkedHashMap<String, String>();

        for(ScrambleRequest scrambleRequest : scrambleRequests) {
            String safeTitle = toFileSafeString(scrambleRequest.title);
            int salt = 0;
            String tempNewSafeTitle = safeTitle;
            while(seenTitles.get(tempNewSafeTitle) != null) {
                tempNewSafeTitle = safeTitle + " (" + (++salt) + ")";
            }
            safeTitle = tempNewSafeTitle;
            seenTitles.put(safeTitle, true);

            // Without passcode, for printing
            String pdfFileName = "Printing/Scramble Sets/" + safeTitle + ".pdf";
            parameters.setFileNameInZip(pdfFileName);
            zipOut.putNextEntry(null, parameters);
            ByteArrayOutputStream pdfByteStream = createPdf(globalTitle, generationDate, scrambleRequest, Translate.DEFAULT_LOCALE, null);
            zipOut.write(pdfByteStream.toByteArray());
            zipOut.closeEntry();

            // With passcode, for computer display
            String passcode = randomPasscode();
            passcodes.put(safeTitle, passcode);

            pdfFileName = computerDisplayFileName + "/" + safeTitle + ".pdf";
            computerDisplayZipParameters.setFileNameInZip(pdfFileName);
            computerDisplayZipOut.putNextEntry(null, computerDisplayZipParameters);
            pdfByteStream = createPdf(globalTitle, generationDate, scrambleRequest, Translate.DEFAULT_LOCALE, passcode);
            computerDisplayZipOut.write(pdfByteStream.toByteArray());
            computerDisplayZipOut.closeEntry();

            String txtFileName = "Interchange/txt/" + safeTitle + ".txt";
            parameters.setFileNameInZip(txtFileName);
            zipOut.putNextEntry(null, parameters);
            zipOut.write(join(stripNewlines(scrambleRequest.getAllScrambles()), "\r\n").getBytes());
            zipOut.closeEntry();

            // i18n is only for fmc
            if (!scrambleRequest.fmc) {
                continue;
            }

            for(Locale locale : Translate.getLocales()) {
                // fewest moves regular sheet
                pdfFileName = "Printing/Fewest Moves - Additional Files/Translations/"+locale.toLanguageTag()+"_"+safeTitle+".pdf";
                parameters.setFileNameInZip(pdfFileName);
                zipOut.putNextEntry(null, parameters);

                ByteArrayOutputStream pdfOut = new ByteArrayOutputStream();
                Rectangle pageSize = PageSize.LETTER;
                Document doc = new Document(pageSize, 0, 0, 75, 75);
                PdfWriter docWriter = PdfWriter.getInstance(doc, pdfOut);

                docWriter.setBoxSize("art", new Rectangle(36, 54, pageSize.getWidth()-36, pageSize.getHeight()-54));

                doc.addCreationDate();
                doc.addProducer();
                if(globalTitle != null) {
                    doc.addTitle(globalTitle);
                }

                doc.open();
                for (int i=0; i<scrambleRequest.scrambles.length; i++) {
                    addFmcSolutionSheet(docWriter, doc, scrambleRequest, globalTitle, i, locale);
                }
                doc.close();

                PdfReader pdfReader = new PdfReader(pdfOut.toByteArray());
                byte[] b = new byte[(int) pdfReader.getFileLength()];
                pdfReader.getSafeFile().readFully(b);
                zipOut.write(b);

                zipOut.closeEntry();

                 // Generic sheet.
                pdfFileName = "Printing/Fewest Moves - Additional Files/Translations/"+locale.toLanguageTag()+"_"+safeTitle+" Solution Sheet.pdf";
                parameters.setFileNameInZip(pdfFileName);
                zipOut.putNextEntry(null, parameters);

                pdfOut = new ByteArrayOutputStream();
                pageSize = PageSize.LETTER;
                doc = new Document(pageSize, 0, 0, 75, 75);
                docWriter = PdfWriter.getInstance(doc, pdfOut);

                docWriter.setBoxSize("art", new Rectangle(36, 54, pageSize.getWidth()-36, pageSize.getHeight()-54));

                doc.addCreationDate();
                doc.addProducer();
                if(globalTitle != null) {
                    doc.addTitle(globalTitle);
                }

                // there's no need to generate 1 per round, since the fields can be filled
                doc.open();
                addGenericFmcSolutionSheet(docWriter, doc, globalTitle, locale);
                doc.close();

                pdfReader = new PdfReader(pdfOut.toByteArray());
                b = new byte[(int) pdfReader.getFileLength()];
                pdfReader.getSafeFile().readFully(b);
                zipOut.write(b);

                zipOut.closeEntry();
            }
        }

        OrderedScrambles.generateOrderedScrambles(globalTitle, generationDate, zipOut, parameters, wcifHelper);

        computerDisplayZipOut.finish();
        computerDisplayZipOut.close();
        parameters.setFileNameInZip(computerDisplayFileName + ".zip");
        zipOut.putNextEntry(null, parameters);
        zipOut.write(computerDisplayBaosZip.toByteArray());
        zipOut.closeEntry();

        String txtFileName = safeGlobalTitle + " - Computer Display PDF Passcodes - SECRET.txt";
        parameters.setFileNameInZip(txtFileName);
        zipOut.putNextEntry(null, parameters);
        StringBuilder builder = new StringBuilder();
        builder.append("SECRET SCRAMBLE SET PASSCODES\r\n");
        if (globalTitle != null) {
            builder.append(globalTitle);
            builder.append("\r\n");
        }
        builder.append("\r\n");
        builder.append("Make sure that only Delegates have access to this file.\r\n");
        builder.append("Give passcodes to scramblers when the corresponding\r\n");
        builder.append("groups begin (but not earlier). If you have to put\r\n");
        builder.append("someone else in charge of the passcodes temporarily,\r\n");
        builder.append("only give them the minimum amount of passcodes needed.\r\n");
        builder.append("\r\n");
        for (Map.Entry<String, String> entry : passcodes.entrySet()) {
            builder.append(String.format("%40s", entry.getKey()));
            builder.append(": ");
            builder.append(entry.getValue());
            builder.append("\r\n");
        }
        zipOut.write(builder.toString().getBytes());
        zipOut.closeEntry();

        String jsonFileName = "Interchange/" + safeGlobalTitle + ".json";
        parameters.setFileNameInZip(jsonFileName);
        zipOut.putNextEntry(null, parameters);
        HashMap<String, Object> jsonObj = new HashMap<String, Object>();
        jsonObj.put("sheets", scrambleRequests);
        jsonObj.put("competitionName", globalTitle);
        jsonObj.put("version", Utils.getProjectName() + "-" + Utils.getVersion());
        jsonObj.put("generationDate", generationDate);
        jsonObj.put("generationUrl", generationUrl);
        if (wcifHelper != null) {
            jsonObj.put("schedule", wcifHelper.getSchedule());
        }
        String json = GSON.toJson(jsonObj);
        zipOut.write(json.getBytes());
        zipOut.closeEntry();

        String jsonpFileName = "Interchange/" + safeGlobalTitle + ".jsonp";
        parameters.setFileNameInZip(jsonpFileName);
        zipOut.putNextEntry(null, parameters);
        String jsonp = "var SCRAMBLES_JSON = " + json + ";";
        zipOut.write(jsonp.getBytes());
        zipOut.closeEntry();

        parameters.setFileNameInZip("Interchange/" + safeGlobalTitle + ".html");
        zipOut.putNextEntry(null, parameters);

        InputStream is = context.getResourceAsStream(HTML_SCRAMBLE_VIEWER);
        BufferedReader in = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line;
        while((line = in.readLine()) != null) {
            line = line.replaceAll("%SCRAMBLES_JSONP_FILENAME%", jsonpFileName);
            sb.append(line).append("\n");
        }
        zipOut.write(sb.toString().getBytes());
        zipOut.closeEntry();

        parameters.setFileNameInZip("Printing/" + safeGlobalTitle + " - All Scrambles.pdf");
        zipOut.putNextEntry(null, parameters);
        // Note that we're not passing the password into this function. It seems pretty silly
        // to put a password protected pdf inside of a password protected zip file.
        ByteArrayOutputStream baos = requestsToPdf(globalTitle, generationDate, scrambleRequests, null);
        zipOut.write(baos.toByteArray());
        zipOut.closeEntry();

        zipOut.finish();
        zipOut.close();

        return baosZip;
    }

    public static ByteArrayOutputStream requestsToPdf(String globalTitle, Date generationDate, ScrambleRequest[] scrambleRequests, String password) throws DocumentException, IOException {
        Document doc = new Document();
        ByteArrayOutputStream totalPdfOutput = new ByteArrayOutputStream();
        PdfSmartCopy totalPdfWriter = new PdfSmartCopy(doc, totalPdfOutput);
        if(password != null) {
            totalPdfWriter.setEncryption(password.getBytes(), password.getBytes(), PdfWriter.ALLOW_PRINTING, PdfWriter.STANDARD_ENCRYPTION_128);
        }

        doc.open();

        PdfContentByte cb = totalPdfWriter.getDirectContent();
        PdfOutline root = cb.getRootOutline();

        HashMap<String, PdfOutline> outlineByPuzzle = new HashMap<String, PdfOutline>();
        boolean expandPuzzleLinks = false;

        int pages = 1;
        for(int i = 0; i < scrambleRequests.length; i++) {
            ScrambleRequest scrambleRequest = scrambleRequests[i];

            String shortName = scrambleRequest.scrambler.getShortName();

            PdfOutline puzzleLink = outlineByPuzzle.get(shortName);
            if(puzzleLink == null) {
                PdfDestination d = new PdfDestination(PdfDestination.FIT);
                puzzleLink = new PdfOutline(root,
                        PdfAction.gotoLocalPage(pages, d, totalPdfWriter), scrambleRequest.scrambler.getLongName(), expandPuzzleLinks);
                outlineByPuzzle.put(shortName, puzzleLink);
            }

            PdfDestination d = new PdfDestination(PdfDestination.FIT);
            new PdfOutline(puzzleLink,
                    PdfAction.gotoLocalPage(pages, d, totalPdfWriter), scrambleRequest.title);

            // We pass a null password, since the resulting pages will be processed further before encryption.
            PdfReader pdfReader = new PdfReader(createPdf(globalTitle, generationDate, scrambleRequest, Translate.DEFAULT_LOCALE, null).toByteArray());
            for(int j = 0; j < scrambleRequest.copies; j++) {
                for(int pageN = 1; pageN <= pdfReader.getNumberOfPages(); pageN++) {
                    PdfImportedPage page = totalPdfWriter.getImportedPage(pdfReader, pageN);
                    totalPdfWriter.addPage(page);
                    pages++;
                }
            }
        }

        doc.close();
        return totalPdfOutput;
    }
    
    @Override
    public int compareTo(ScrambleRequest other) {
        return this.roundStartTime.compareTo(other.roundStartTime);
    }
}
