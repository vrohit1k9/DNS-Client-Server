import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.time.*;
import java.time.format.*;
import java.util.*;

public class DNSGui extends JFrame {

    // ── Colour palette – high contrast, vibrant ─────────────────────────────
    static final Color BG_DARK       = new Color(15, 20, 30);   // slightly lighter base
    static final Color BG_PANEL      = new Color(25, 33, 48);   // header / footer
    static final Color BG_CARD       = new Color(34, 44, 62);   // cards
    static final Color BG_INPUT      = new Color(18, 24, 36);   // text fields & log
    static final Color BG_TAB_SEL    = new Color(34, 44, 62);   // selected tab bg
    static final Color BG_TAB_UNSEL  = new Color(20, 27, 40);   // unselected tab bg
    static final Color ACCENT_BLUE   = new Color( 90, 165, 255);
    static final Color ACCENT_GREEN  = new Color( 55, 230,  95);
    static final Color ACCENT_RED    = new Color(255,  90,  80);
    static final Color ACCENT_AMBER  = new Color(255, 195,  40);
    static final Color ACCENT_PURPLE = new Color(210, 160, 255);
    static final Color ACCENT_CYAN   = new Color( 60, 220, 230);
    static final Color TEXT_PRIMARY  = new Color(240, 246, 255); // near-white
    static final Color TEXT_MUTED    = new Color(180, 192, 210); // clearly readable
    static final Color TEXT_DIM      = new Color(130, 145, 168); // secondary
    static final Color BORDER_BRIGHT = new Color( 85, 110, 150); // visible borders
    static final Color BORDER_DIM    = new Color( 48,  62,  85);
    static final Color LOG_SEP       = new Color(110, 145, 195);

    // ── Fonts ────────────────────────────────────────────────────────────────
    static final Font FONT_TITLE  = new Font("Segoe UI", Font.BOLD,  22);
    static final Font FONT_BODY   = new Font("Segoe UI", Font.PLAIN, 13);
    static final Font FONT_BOLD   = new Font("Segoe UI", Font.BOLD,  13);
    static final Font FONT_MONO   = new Font("Consolas", Font.PLAIN, 12);
    static final Font FONT_SMALL  = new Font("Segoe UI", Font.PLAIN, 11);
    static final Font FONT_BADGE  = new Font("Segoe UI", Font.BOLD,  11);
    static final Font FONT_VAL    = new Font("Segoe UI", Font.BOLD,  28);

    // ── Server state ─────────────────────────────────────────────────────────
    private volatile boolean         serverRunning = false;
    private          Thread          serverThread;
    private          DatagramSocket  serverSocket;
    private          Map<String,String> dnsRecords;
    private          DNSCache        cache;
    private final    Map<String,String> displayCache = new LinkedHashMap<>();

    // ── Server tab widgets ───────────────────────────────────────────────────
    private JButton           btnStartStop;
    private JLabel            lblServerStatus;
    private JTextPane         serverLogPane;
    private StyledDocument    serverDoc;
    private JLabel            lblHits, lblMisses, lblEntries, lblQueries, lblHitRate;
    private DefaultTableModel cacheTableModel;
    private int               totalQueries = 0;
    private javax.swing.Timer ttlTimer;

    // ── Client tab widgets ───────────────────────────────────────────────────
    private JTextField        tfDomain;
    private JButton           btnLookup;
    private JLabel            lblResultIP, lblResultStatus, lblResultSource, lblResultTime;
    private JTextPane         clientLogPane;
    private StyledDocument    clientDoc;
    private DefaultTableModel historyTableModel;
    private DefaultTableModel queryTableModel;   // dns_queries table (new)

    private final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) {}
            new DNSGui().setVisible(true);
        });
    }

    public DNSGui() {
        super("Custom DNS  |  Server & Client");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1060, 740);
        setMinimumSize(new Dimension(900, 600));
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG_DARK);
        setContentPane(buildMainPanel());
        loadSearchHistoryFromMongoDB();
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) { stopServer(); }
        });
    }
    // ════════════════════════════════════════════════════════════════════════
    // TOP LEVEL LAYOUT
    // ════════════════════════════════════════════════════════════════════════
    private JPanel buildMainPanel() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG_DARK);
        root.add(buildHeader(),  BorderLayout.NORTH);
        root.add(buildTabs(),    BorderLayout.CENTER);
        root.add(buildFooter(),  BorderLayout.SOUTH);
        return root;
    }

    private JPanel buildHeader() {
        JPanel h = new JPanel(new BorderLayout());
        h.setBackground(BG_PANEL);
        h.setBorder(new CompoundBorder(
            new MatteBorder(0,0,2,0, ACCENT_BLUE),
            new EmptyBorder(12,20,12,20)));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        left.setOpaque(false);
        // Globe icon replaced by text since emoji rendering varies
        JLabel icon = new JLabel("[ DNS ]");
        icon.setFont(new Font("Consolas", Font.BOLD, 20));
        icon.setForeground(ACCENT_CYAN);

        JLabel title = new JLabel("Custom DNS");
        title.setFont(FONT_TITLE);
        title.setForeground(TEXT_PRIMARY);

        JLabel sub = new JLabel("  Client  |  Server  |  Cache");
        sub.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        sub.setForeground(new Color(190, 205, 230));  // brighter than TEXT_MUTED

        left.add(icon); left.add(title); left.add(sub);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);
        right.add(badge("UDP : 5454", ACCENT_BLUE));
        right.add(badge("Port : localhost", ACCENT_PURPLE));
        MongoHistoryService mongo = MongoHistoryService.getInstance();
        if (mongo.isConnected()) {
            right.add(badge("MongoDB : Connected", ACCENT_GREEN));
        } else {
            right.add(badge("MongoDB : Offline", TEXT_MUTED));
        }

        h.add(left, BorderLayout.WEST);
        h.add(right, BorderLayout.EAST);
        return h;
    }

    private JPanel buildFooter() {
        JPanel f = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 7));
        f.setBackground(BG_PANEL);
        f.setBorder(new MatteBorder(1,0,0,0, BORDER_BRIGHT));
        JLabel l = new JLabel(
            "Protocol:  DNS|REQUEST|<domain>  ->  DNS|RESPONSE|<domain>|<ip>   |   Error: DNS|ERROR|<domain>|DOMAIN_NOT_FOUND");
        l.setFont(FONT_SMALL);
        l.setForeground(TEXT_MUTED);
        f.add(l);
        return f;
    }

    private JTabbedPane buildTabs() {
        JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP) {
            // Override to force our own tab painting so system L&F cannot override colours
            @Override
            public void updateUI() {
                setUI(new javax.swing.plaf.basic.BasicTabbedPaneUI() {
                    @Override
                    protected void paintTabBackground(Graphics g, int tabPlacement,
                            int tabIndex, int x, int y, int w, int h, boolean isSelected) {
                        g.setColor(isSelected ? BG_TAB_SEL : BG_TAB_UNSEL);
                        g.fillRect(x, y, w, h);
                    }
                    @Override
                    protected void paintTabBorder(Graphics g, int tabPlacement,
                            int tabIndex, int x, int y, int w, int h, boolean isSelected) {
                        Graphics2D g2 = (Graphics2D) g;
                        if (isSelected) {
                            // bright cyan underline on selected tab
                            g2.setColor(ACCENT_CYAN);
                            g2.setStroke(new BasicStroke(3f));
                            g2.drawLine(x + 2, y + h - 1, x + w - 2, y + h - 1);
                        } else {
                            g2.setColor(BORDER_BRIGHT);
                            g2.setStroke(new BasicStroke(1f));
                            g2.drawRect(x, y, w - 1, h - 1);
                        }
                    }
                    @Override
                    protected void paintFocusIndicator(Graphics g, int tabPlacement,
                            Rectangle[] rects, int tabIndex,
                            Rectangle iconRect, Rectangle textRect, boolean isSelected) {}
                    @Override
                    protected int getTabLabelShiftY(int tabPlacement, int tabIndex, boolean isSelected) { return 0; }
                });
            }
        };
        tabs.setBackground(BG_DARK);
        tabs.setForeground(TEXT_PRIMARY);
        tabs.setFont(new Font("Segoe UI", Font.BOLD, 14));
        tabs.addTab("    Server    ", buildServerTab());
        tabs.addTab("    Client    ", buildClientTab());
        tabs.addTab("  Query Log  ",  buildQueryLogTab());
        // Unselected tabs: visible but dimmer; selected tab: full brightness
        tabs.setForegroundAt(0, new Color(210, 220, 240));
        tabs.setForegroundAt(1, new Color(210, 220, 240));
        tabs.setForegroundAt(2, new Color(210, 220, 240));
        tabs.setBackgroundAt(0, BG_TAB_UNSEL);
        tabs.setBackgroundAt(1, BG_TAB_UNSEL);
        tabs.setBackgroundAt(2, BG_TAB_UNSEL);
        return tabs;
    }

    // ════════════════════════════════════════════════════════════════════════
    // SERVER TAB
    // ════════════════════════════════════════════════════════════════════════
    private JPanel buildServerTab() {
        JPanel tab = new JPanel(new BorderLayout(8, 10));
        tab.setBackground(BG_DARK);
        tab.setBorder(new EmptyBorder(14,16,14,16));
        tab.add(buildServerTopRow(), BorderLayout.NORTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
            buildServerLogCard(), buildCacheCard());
        split.setDividerLocation(580); split.setDividerSize(6);
        split.setBackground(BG_DARK); split.setBorder(null); split.setOpaque(false);
        tab.add(split, BorderLayout.CENTER);
        return tab;
    }

    private JPanel buildServerTopRow() {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(0,0,10,0));

        // ── Control card ─────────────────────────────────────────────────────
        JPanel ctrl = card(BORDER_BRIGHT);
        ctrl.setLayout(new FlowLayout(FlowLayout.LEFT, 14, 12));

        btnStartStop = accentButton("  START SERVER  ", ACCENT_GREEN);
        btnStartStop.addActionListener(e -> toggleServer());

        lblServerStatus = statusBadge("OFFLINE", ACCENT_RED);

        JButton clrLog   = ghostButton("Clear Log");
        clrLog.addActionListener(e -> clearDoc(serverDoc));
        JButton clrCache = ghostButton("Flush Cache");
        clrCache.addActionListener(e -> {
            if (cache != null) { cache.clear(); displayCache.clear(); refreshStats(); }
        });

        ctrl.add(btnStartStop);
        ctrl.add(Box.createHorizontalStrut(6));
        ctrl.add(lblServerStatus);
        ctrl.add(Box.createHorizontalStrut(14));
        ctrl.add(clrLog);
        ctrl.add(clrCache);

        // ── Stats row ─────────────────────────────────────────────────────────
        JPanel stats = new JPanel(new GridLayout(1, 5, 8, 0));
        stats.setOpaque(false);

        JPanel qCard = statPanel("Total Queries",  "0", ACCENT_BLUE);
        JPanel hCard = statPanel("Cache Hits",     "0", ACCENT_GREEN);
        JPanel mCard = statPanel("Cache Misses",   "0", ACCENT_AMBER);
        JPanel rCard = statPanel("Hit Rate",       "0%", ACCENT_CYAN);
        JPanel eCard = statPanel("Cached Entries", "0", ACCENT_PURPLE);

        lblQueries = (JLabel) qCard.getClientProperty("val");
        lblHits    = (JLabel) hCard.getClientProperty("val");
        lblMisses  = (JLabel) mCard.getClientProperty("val");
        lblHitRate = (JLabel) rCard.getClientProperty("val");
        lblEntries = (JLabel) eCard.getClientProperty("val");

        stats.add(qCard); stats.add(hCard); stats.add(mCard); stats.add(rCard); stats.add(eCard);

        row.add(ctrl,  BorderLayout.WEST);
        row.add(stats, BorderLayout.CENTER);
        return row;
    }

    private JPanel statPanel(String title, String init, Color accent) {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBackground(BG_CARD);
        p.setBorder(new CompoundBorder(
            new LineBorder(accent, 1, true),
            new EmptyBorder(12, 16, 12, 16)));

        JPanel top = new JPanel();
        top.setBackground(accent);
        top.setPreferredSize(new Dimension(0, 4));

        JLabel val = new JLabel(init);
        val.setFont(FONT_VAL);
        val.setForeground(accent);

        JLabel lbl = new JLabel(title);
        lbl.setFont(FONT_SMALL);
        lbl.setForeground(TEXT_MUTED);

        p.add(top, BorderLayout.NORTH);
        p.add(val, BorderLayout.CENTER);
        p.add(lbl, BorderLayout.SOUTH);
        p.putClientProperty("val", val);
        return p;
    }

    private JPanel buildServerLogCard() {
        JPanel c = card(BORDER_BRIGHT);
        c.setLayout(new BorderLayout(0, 8));
        c.setBorder(cardBorder(ACCENT_BLUE));
        c.add(accentHeader("  SERVER LOG", ACCENT_BLUE), BorderLayout.NORTH);
        serverLogPane = logPane();
        serverDoc = serverLogPane.getStyledDocument();
        c.add(scroll(serverLogPane), BorderLayout.CENTER);
        return c;
    }

    private JPanel buildCacheCard() {
        JPanel c = card(BORDER_BRIGHT);
        c.setLayout(new BorderLayout(0, 8));
        c.setBorder(cardBorder(ACCENT_PURPLE));
        c.add(accentHeader("  CACHE VIEWER (TTL COUNTDOWN)", ACCENT_PURPLE), BorderLayout.NORTH);

        cacheTableModel = new DefaultTableModel(new String[]{"Domain", "IP Address", "TTL Left", "Status"}, 0) {
            public boolean isCellEditable(int r, int col) { return false; }
        };
        JTable t = styledTable(cacheTableModel);
        t.getColumnModel().getColumn(0).setPreferredWidth(135);
        t.getColumnModel().getColumn(1).setPreferredWidth(115);
        t.getColumnModel().getColumn(2).setPreferredWidth(65);
        t.getColumnModel().getColumn(3).setPreferredWidth(65);

        t.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            public Component getTableCellRendererComponent(
                    JTable tbl, Object v, boolean sel, boolean foc, int r, int cc) {
                super.getTableCellRendererComponent(tbl, v, sel, foc, r, cc);
                setFont(new Font("Consolas", Font.BOLD, 12));
                setForeground(ACCENT_AMBER);
                setBackground(sel ? new Color(80,160,255,50) : r%2==0 ? BG_CARD : new Color(22,30,44));
                setHorizontalAlignment(SwingConstants.CENTER);
                return this;
            }
        });

        t.getColumnModel().getColumn(3).setCellRenderer(new DefaultTableCellRenderer() {
            public Component getTableCellRendererComponent(
                    JTable tbl, Object v, boolean sel, boolean foc, int r, int cc) {
                super.getTableCellRendererComponent(tbl, v, sel, foc, r, cc);
                String s = v == null ? "" : v.toString();
                setFont(new Font("Segoe UI", Font.BOLD, 11));
                if ("ACTIVE".equals(s)) setForeground(ACCENT_GREEN);
                else setForeground(ACCENT_RED);
                setBackground(sel ? new Color(80,160,255,50) : r%2==0 ? BG_CARD : new Color(22,30,44));
                setHorizontalAlignment(SwingConstants.CENTER);
                return this;
            }
        });

        c.add(scroll(t), BorderLayout.CENTER);
        return c;
    }

    // ════════════════════════════════════════════════════════════════════════
    // CLIENT TAB
    // ════════════════════════════════════════════════════════════════════════
    private JPanel buildClientTab() {
        JPanel tab = new JPanel(new BorderLayout(8, 10));
        tab.setBackground(BG_DARK);
        tab.setBorder(new EmptyBorder(14, 16, 14, 16));
        tab.add(buildQueryCard(), BorderLayout.NORTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
            buildHistoryCard(), buildClientLogCard());
        split.setDividerLocation(500); split.setDividerSize(6);
        split.setBorder(null); split.setOpaque(false);
        tab.add(split, BorderLayout.CENTER);
        return tab;
    }

    private JPanel buildQueryCard() {
        JPanel c = card(BORDER_BRIGHT);
        c.setLayout(new BorderLayout(10, 0));
        c.setBorder(cardBorder(ACCENT_CYAN));
        c.setPreferredSize(new Dimension(0, 175));
        c.add(accentHeader("  DNS LOOKUP", ACCENT_CYAN), BorderLayout.NORTH);

        // Input row
        JPanel inputRow = new JPanel(new BorderLayout(10, 0));
        inputRow.setOpaque(false);
        inputRow.setBorder(new EmptyBorder(10, 0, 8, 0));

        tfDomain = new JTextField();
        tfDomain.setBackground(BG_INPUT);
        tfDomain.setForeground(TEXT_PRIMARY);
        tfDomain.setCaretColor(ACCENT_CYAN);
        tfDomain.setFont(new Font("Consolas", Font.PLAIN, 16));
        tfDomain.setBorder(new CompoundBorder(
            new LineBorder(BORDER_BRIGHT, 1, true),
            new EmptyBorder(9, 12, 9, 12)));
        tfDomain.addActionListener(e -> doLookup());

        btnLookup = accentButton("  RESOLVE  ", ACCENT_CYAN);
        btnLookup.setPreferredSize(new Dimension(140, 44));
        btnLookup.setForeground(BG_DARK);
        btnLookup.addActionListener(e -> doLookup());

        inputRow.add(tfDomain, BorderLayout.CENTER);
        inputRow.add(btnLookup, BorderLayout.EAST);

        // Result row
        JPanel resultRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        resultRow.setOpaque(false);
        lblResultIP     = new JLabel("--");
        lblResultIP.setFont(new Font("Consolas", Font.BOLD, 16));
        lblResultIP.setForeground(TEXT_MUTED);
        lblResultStatus = statusBadge("IDLE", TEXT_DIM);
        lblResultTime   = new JLabel("");
        lblResultTime.setFont(new Font("Consolas", Font.BOLD, 13));
        lblResultTime.setForeground(ACCENT_GREEN);
        lblResultSource = new JLabel("");
        lblResultSource.setFont(FONT_SMALL);
        lblResultSource.setForeground(ACCENT_AMBER);

        resultRow.add(mkLabel("IP :", TEXT_MUTED, FONT_BOLD));
        resultRow.add(lblResultIP);
        resultRow.add(Box.createHorizontalStrut(6));
        resultRow.add(lblResultStatus);
        resultRow.add(Box.createHorizontalStrut(6));
        resultRow.add(lblResultTime);
        resultRow.add(Box.createHorizontalStrut(6));
        resultRow.add(lblResultSource);

        JPanel centre = new JPanel(new BorderLayout(0, 4));
        centre.setOpaque(false);
        centre.add(inputRow,  BorderLayout.NORTH);
        centre.add(resultRow, BorderLayout.CENTER);
        c.add(centre, BorderLayout.CENTER);
        return c;
    }

    private JPanel buildHistoryCard() {
        JPanel c = card(BORDER_BRIGHT);
        c.setLayout(new BorderLayout(0, 8));
        c.setBorder(cardBorder(ACCENT_GREEN));

        JPanel hdr = new JPanel(new BorderLayout());
        hdr.setOpaque(false);
        hdr.add(accentHeader("  QUERY HISTORY", ACCENT_GREEN), BorderLayout.WEST);
        JButton clr = ghostButton("Clear");
        clr.addActionListener(e -> historyTableModel.setRowCount(0));
        JButton reload = ghostButton("Reload Mongo");
        reload.addActionListener(e -> loadSearchHistoryFromMongoDB());
        JButton btnImportChrome = ghostButton("Import Chrome");
        btnImportChrome.addActionListener(e -> {
            btnImportChrome.setEnabled(false);
            log(clientDoc, "\n========================================\n", LOG_SEP);
            log(clientDoc, "  [Chrome Import] Reading local Chrome history...\n", ACCENT_CYAN);
            SwingWorker<Integer, String> worker = new SwingWorker<>() {
                @Override
                protected Integer doInBackground() {
                    return ChromeHistoryImporter.importHistory(this::publish);
                }
                @Override
                protected void process(java.util.List<String> chunks) {
                    for (String msg : chunks) {
                        log(clientDoc, "  " + msg + "\n", TEXT_MUTED);
                    }
                }
                @Override
                protected void done() {
                    btnImportChrome.setEnabled(true);
                    loadSearchHistoryFromMongoDB();
                }
            };
            worker.execute();
        });
        JPanel bw = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        bw.setOpaque(false);
        bw.add(btnImportChrome);
        bw.add(reload);
        bw.add(clr);
        hdr.add(bw, BorderLayout.EAST);
        c.add(hdr, BorderLayout.NORTH);

        historyTableModel = new DefaultTableModel(
            new String[]{"Time", "Domain", "IP", "Source", "Latency", "Status"}, 0) {
            public boolean isCellEditable(int r, int col) { return false; }
        };
        JTable t = styledTable(historyTableModel);
        t.getColumnModel().getColumn(0).setPreferredWidth(55);
        t.getColumnModel().getColumn(1).setPreferredWidth(135);
        t.getColumnModel().getColumn(2).setPreferredWidth(115);
        t.getColumnModel().getColumn(3).setPreferredWidth(85);
        t.getColumnModel().getColumn(4).setPreferredWidth(60);
        t.getColumnModel().getColumn(5).setPreferredWidth(75);

        // Source column renderer (CACHE in green, MongoDB in purple, DNS in cyan)
        t.getColumnModel().getColumn(3).setCellRenderer(new DefaultTableCellRenderer() {
            public Component getTableCellRendererComponent(
                    JTable tbl, Object v, boolean sel, boolean foc, int r, int cc) {
                super.getTableCellRendererComponent(tbl, v, sel, foc, r, cc);
                String s = v == null ? "" : v.toString();
                setFont(new Font("Segoe UI", Font.BOLD, 11));
                if (s.contains("CACHE"))          setForeground(ACCENT_GREEN);
                else if (s.contains("Mongo"))     setForeground(ACCENT_PURPLE);
                else if (s.contains("DNS") || s.contains("REAL")) setForeground(ACCENT_CYAN);
                else                              setForeground(ACCENT_PURPLE);
                setBackground(sel ? new Color(80,160,255,50) : r%2==0 ? BG_CARD : new Color(22,30,44));
                setBorder(new EmptyBorder(0, 6, 0, 6));
                return this;
            }
        });

        // Latency column renderer (<=2ms green, else amber)
        t.getColumnModel().getColumn(4).setCellRenderer(new DefaultTableCellRenderer() {
            public Component getTableCellRendererComponent(
                    JTable tbl, Object v, boolean sel, boolean foc, int r, int cc) {
                super.getTableCellRendererComponent(tbl, v, sel, foc, r, cc);
                String s = v == null ? "" : v.toString();
                setFont(new Font("Consolas", Font.BOLD, 12));
                if (s.startsWith("0") || s.startsWith("1 ") || s.startsWith("2 ") || s.startsWith("1ms") || s.startsWith("0ms")) {
                    setForeground(ACCENT_GREEN);
                } else {
                    setForeground(ACCENT_AMBER);
                }
                setBackground(sel ? new Color(80,160,255,50) : r%2==0 ? BG_CARD : new Color(22,30,44));
                setHorizontalAlignment(SwingConstants.CENTER);
                return this;
            }
        });

        // Status column renderer
        t.getColumnModel().getColumn(5).setCellRenderer(new DefaultTableCellRenderer() {
            public Component getTableCellRendererComponent(
                    JTable tbl, Object v, boolean sel, boolean foc, int r, int cc) {
                super.getTableCellRendererComponent(tbl, v, sel, foc, r, cc);
                String s = v == null ? "" : v.toString();
                setFont(new Font("Segoe UI", Font.BOLD, 11));
                if (s.contains("HISTORY HIT"))    setForeground(ACCENT_PURPLE);
                else if (s.contains("NEW SEARCH"))setForeground(ACCENT_CYAN);
                else if (s.contains("CACHE HIT") || s.contains("OK")) setForeground(ACCENT_GREEN);
                else if (s.contains("NOT") || s.contains("ERR") || s.contains("TIME"))
                                                  setForeground(ACCENT_RED);
                else                              setForeground(ACCENT_AMBER);
                setBackground(sel ? new Color(80,160,255,50) : r%2==0 ? BG_CARD : new Color(22,30,44));
                setHorizontalAlignment(SwingConstants.CENTER);
                return this;
            }
        });
        c.add(scroll(t), BorderLayout.CENTER);
        return c;
    }

    private JPanel buildClientLogCard() {
        JPanel c = card(BORDER_BRIGHT);
        c.setLayout(new BorderLayout(0, 8));
        c.setBorder(cardBorder(ACCENT_AMBER));

        JPanel hdr = new JPanel(new BorderLayout());
        hdr.setOpaque(false);
        hdr.add(accentHeader("  CLIENT LOG", ACCENT_AMBER), BorderLayout.WEST);
        JButton clr = ghostButton("Clear");
        clr.addActionListener(e -> clearDoc(clientDoc));
        JPanel bw = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        bw.setOpaque(false); bw.add(clr);
        hdr.add(bw, BorderLayout.EAST);
        c.add(hdr, BorderLayout.NORTH);

        clientLogPane = logPane();
        clientDoc = clientLogPane.getStyledDocument();
        c.add(scroll(clientLogPane), BorderLayout.CENTER);
        return c;
    }
    // ════════════════════════════════════════════════════════════════════════
    // SERVER LOGIC
    // ════════════════════════════════════════════════════════════════════════
    private void toggleServer() {
        if (!serverRunning) startServer(); else stopServer();
    }

    private void startServer() {
        dnsRecords = loadRecords("dns_records.txt");
        if (dnsRecords == null) {
            JOptionPane.showMessageDialog(this,
                "Cannot load dns_records.txt!\nEnsure the file is in the same folder as DNSGui.class.",
                "File Not Found", JOptionPane.ERROR_MESSAGE);
            return;
        }
        cache = new DNSCache();
        displayCache.clear();
        totalQueries = 0;
        serverRunning = true;
        setServerUI(true);

        ttlTimer = new javax.swing.Timer(1000, e -> {
            if (serverRunning && cache != null) {
                SwingUtilities.invokeLater(this::refreshStats);
            }
        });
        ttlTimer.start();

        serverThread = new Thread(() -> {
            try {
                serverSocket = new DatagramSocket(5454);
                log(serverDoc, ">>> SERVER ONLINE  on  UDP 0.0.0.0:5454\n", ACCENT_GREEN);
                log(serverDoc, "    Records loaded : " + dnsRecords.size() + "\n", TEXT_MUTED);
                log(serverDoc, "    TTL Caching    : Enabled (" + DNSCache.DEFAULT_TTL_SECONDS + "s default)\n", ACCENT_CYAN);
                MongoHistoryService mhs = MongoHistoryService.getInstance();
                if (mhs.isConnected()) {
                    log(serverDoc, "    MongoDB History: Connected (" + mhs.getDatabaseName() + "." + mhs.getCollectionName() + ")\n", ACCENT_PURPLE);
                } else {
                    log(serverDoc, "    MongoDB History: Offline (Operating in fallback mode)\n", ACCENT_AMBER);
                }
                log(serverDoc, "    Waiting for queries...\n", TEXT_MUTED);
                byte[] buf = new byte[1024];
                while (serverRunning) {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    serverSocket.setSoTimeout(1000);
                    try { serverSocket.receive(pkt); }
                    catch (SocketTimeoutException ignored) { continue; }
                    final DatagramPacket copy = new DatagramPacket(
                        Arrays.copyOf(pkt.getData(), pkt.getLength()),
                        pkt.getLength(), pkt.getAddress(), pkt.getPort());
                    new Thread(() -> handleRequest(copy)).start();
                    buf = new byte[1024];
                }
            } catch (BindException be) {
                log(serverDoc, "!!! Port 5454 already in use!  Stop the other server.\n", ACCENT_RED);
                serverRunning = false;
                SwingUtilities.invokeLater(() -> setServerUI(false));
            } catch (Exception e) {
                if (serverRunning)
                    log(serverDoc, "!!! Server error: " + e.getMessage() + "\n", ACCENT_RED);
            } finally {
                if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
            }
        }, "DNS-Server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private void stopServer() {
        if (!serverRunning) return;
        serverRunning = false;
        if (ttlTimer != null) ttlTimer.stop();
        if (serverSocket != null) serverSocket.close();
        log(serverDoc, "<<< Server stopped.\n", ACCENT_AMBER);
        SwingUtilities.invokeLater(() -> setServerUI(false));
    }

    private void handleRequest(DatagramPacket pkt) {
        long t0   = System.currentTimeMillis();
        String raw  = new String(pkt.getData(), 0, pkt.getLength()).trim();
        String from = pkt.getAddress().getHostAddress();

        log(serverDoc, "\n========================================\n", LOG_SEP);
        log(serverDoc, "  From   : " + from + "\n",  TEXT_MUTED);
        log(serverDoc, "  Msg    : " + raw  + "\n",  ACCENT_BLUE);

        String response;
        String[] parts = raw.split("\\|");

        if (parts.length != 3 || !"DNS".equals(parts[0]) || !"REQUEST".equals(parts[1])) {
            response = "DNS|ERROR|UNKNOWN|INVALID_REQUEST_FORMAT";
            log(serverDoc, "  Status : INVALID FORMAT\n", ACCENT_RED);
        } else {
            String domain = parts[2].trim().toLowerCase();
            log(serverDoc, "  Domain : " + domain + "\n", TEXT_PRIMARY);

            // 1. Check DNSCache first
            DNSCache.Entry cached = cache.getEntry(domain);
            if (cached != null) {
                long ttlLeft = cached.ttlRemainingSeconds();
                log(serverDoc, "  Cache  : HIT (TTL: " + ttlLeft + "s remaining) -> " + cached.ip + "\n", ACCENT_GREEN);
                response = "DNS|RESPONSE|" + domain + "|" + cached.ip + "|CACHE|" + ttlLeft;
            } else {
                log(serverDoc, "  Cache  : MISS\n", ACCENT_AMBER);

                // 2. Check MongoDB search history
                MongoHistoryService mongo = MongoHistoryService.getInstance();
                SearchRecord historyHit = mongo.findAndRecordHit(domain);
                if (historyHit != null) {
                    cache.put(domain, historyHit.getIp());
                    log(serverDoc, "  MongoDB: HIT (Search count: " + historyHit.getSearchCount() + ") -> " + historyHit.getIp() + "\n", ACCENT_PURPLE);
                    response = "DNS|RESPONSE|" + domain + "|" + historyHit.getIp() + "|MONGODB|" + historyHit.getSearchCount();
                } else {
                    log(serverDoc, "  MongoDB: NOT FOUND\n", TEXT_MUTED);

                    // 3. Use existing DNS resolution logic
                    String ip = dnsRecords.get(domain);
                    if (ip != null) {
                        cache.put(domain, ip);
                        mongo.saveNewSearch(domain, ip, "DNS_LOOKUP");
                        log(serverDoc, "  Source : LOCAL DATABASE -> " + ip + " (Saved to MongoDB & cached)\n", ACCENT_PURPLE);
                        response = "DNS|RESPONSE|" + domain + "|" + ip + "|DNS_LOOKUP|" + cache.getDefaultTTL();
                    } else {
                        try {
                            InetAddress addr = InetAddress.getByName(domain);
                            ip = addr.getHostAddress();
                            cache.put(domain, ip);
                            mongo.saveNewSearch(domain, ip, "DNS_LOOKUP");
                            log(serverDoc, "  Source : REAL DNS -> " + ip + " (Saved to MongoDB & cached)\n", ACCENT_CYAN);
                            response = "DNS|RESPONSE|" + domain + "|" + ip + "|DNS_LOOKUP|" + cache.getDefaultTTL();
                        } catch (UnknownHostException ex) {
                            log(serverDoc, "  Status : NOT FOUND\n", ACCENT_RED);
                            response = "DNS|ERROR|" + domain + "|DOMAIN_NOT_FOUND";
                        }
                    }
                }
            }
        }

        try {
            byte[] rb = response.getBytes();
            DatagramPacket rp = new DatagramPacket(rb, rb.length, pkt.getAddress(), pkt.getPort());
            synchronized (serverSocket) { serverSocket.send(rp); }
            long ms = System.currentTimeMillis() - t0;
            log(serverDoc, "  Time   : " + ms + " ms\n",    TEXT_MUTED);
            log(serverDoc, "  Result : " + response + "\n", ACCENT_GREEN);
        } catch (Exception e) {
            log(serverDoc, "  Send error: " + e.getMessage() + "\n", ACCENT_RED);
        }

        totalQueries++;
        SwingUtilities.invokeLater(this::refreshStats);
    }

    private void setServerUI(boolean running) {
        if (running) {
            btnStartStop.setText("  STOP SERVER  ");
            btnStartStop.setBackground(ACCENT_RED);
            lblServerStatus.setText("  ONLINE  ");
            lblServerStatus.setBackground(ACCENT_GREEN);
            lblServerStatus.setForeground(BG_DARK);
        } else {
            btnStartStop.setText("  START SERVER  ");
            btnStartStop.setBackground(ACCENT_GREEN);
            lblServerStatus.setText("  OFFLINE  ");
            lblServerStatus.setBackground(ACCENT_RED);
            lblServerStatus.setForeground(TEXT_PRIMARY);
        }
    }

    private void refreshStats() {
        if (cache == null) return;
        lblQueries.setText(String.valueOf(totalQueries));
        lblHits.setText(String.valueOf(cache.getHits()));
        lblMisses.setText(String.valueOf(cache.getMisses()));
        if (lblHitRate != null) {
            lblHitRate.setText(String.format("%.0f%%", cache.getHitRate()));
        }
        lblEntries.setText(String.valueOf(cache.size()));

        cacheTableModel.setRowCount(0);
        Map<String, DNSCache.Entry> snap = cache.snapshot();
        for (Map.Entry<String, DNSCache.Entry> e : snap.entrySet()) {
            DNSCache.Entry entry = e.getValue();
            long left = entry.ttlRemainingSeconds();
            String status = entry.isExpired() ? "EXPIRED" : "ACTIVE";
            String ttlStr = entry.isExpired() ? "0s" : left + "s";
            cacheTableModel.addRow(new Object[]{e.getKey(), entry.ip, ttlStr, status});
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // CLIENT LOGIC
    // ════════════════════════════════════════════════════════════════════════
    private void doLookup() {
        String domain = tfDomain.getText().trim().toLowerCase();
        if (domain.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter a domain name.",
                "Input Required", JOptionPane.WARNING_MESSAGE);
            return;
        }
        btnLookup.setEnabled(false);
        lblResultStatus.setText("  Querying...  ");
        lblResultStatus.setBackground(ACCENT_AMBER);
        lblResultStatus.setForeground(BG_DARK);
        lblResultIP.setText("...");
        lblResultIP.setForeground(TEXT_MUTED);
        lblResultTime.setText("");
        lblResultSource.setText("");

        String ts = java.time.LocalTime.now().format(TS);
        log(clientDoc, "\n========================================\n", LOG_SEP);
        log(clientDoc, "  [" + ts + "]  Query : " + domain + "\n", ACCENT_CYAN);
        log(clientDoc, "  Sending to 127.0.0.1:5454 via UDP\n", TEXT_MUTED);

        SwingWorker<Object[],Void> w = new SwingWorker<Object[],Void>() {
            protected Object[] doInBackground() {
                String req = "DNS|REQUEST|" + domain;
                long t0 = System.currentTimeMillis();
                try (DatagramSocket s = new DatagramSocket()) {
                    s.setSoTimeout(5000);
                    InetAddress addr = InetAddress.getByName("127.0.0.1");
                    byte[] sb = req.getBytes();
                    s.send(new DatagramPacket(sb, sb.length, addr, 5454));
                    byte[] rb = new byte[1024];
                    DatagramPacket rp = new DatagramPacket(rb, rb.length);
                    s.receive(rp);
                    long elapsed = System.currentTimeMillis() - t0;
                    String[] parts = new String(rp.getData(), 0, rp.getLength()).trim().split("\\|");
                    return new Object[]{ parts, elapsed };
                } catch (SocketTimeoutException e) {
                    return new Object[]{ new String[]{"TIMEOUT"}, System.currentTimeMillis() - t0 };
                } catch (Exception e) {
                    return new Object[]{ new String[]{"ERR", e.getMessage()}, System.currentTimeMillis() - t0 };
                }
            }
            protected void done() {
                try {
                    Object[] res = get();
                    String[] parts = (String[]) res[0];
                    long elapsed = (Long) res[1];
                    handleClientResponse(domain, parts, elapsed, ts);
                }
                catch (Exception ex) { log(clientDoc, "  Error: " + ex.getMessage() + "\n", ACCENT_RED); }
                finally { btnLookup.setEnabled(true); }
            }
        };
        w.execute();
    }

    private void handleClientResponse(String domain, String[] parts, long elapsedMs, String ts) {
        if (parts.length == 1 && "TIMEOUT".equals(parts[0])) {
            lblResultIP.setText("--"); lblResultIP.setForeground(ACCENT_RED);
            lblResultStatus.setText("  TIMEOUT  "); lblResultStatus.setBackground(ACCENT_RED); lblResultStatus.setForeground(TEXT_PRIMARY);
            lblResultTime.setText("");
            lblResultSource.setText("Is the server running?");
            log(clientDoc, "  !!! Timed out (5s) — start the server first!\n", ACCENT_RED);
            historyTableModel.insertRow(0, new Object[]{ts, domain, "--", "--", elapsedMs + " ms", "TIMEOUT"});
            return;
        }
        if (parts.length < 3 || !"DNS".equals(parts[0])) {
            lblResultStatus.setText("  ERROR  "); lblResultStatus.setBackground(ACCENT_RED); lblResultStatus.setForeground(TEXT_PRIMARY);
            lblResultTime.setText("");
            lblResultSource.setText("");
            log(clientDoc, "  !!! Invalid response from server\n", ACCENT_RED);
            historyTableModel.insertRow(0, new Object[]{ts, domain, "--", "--", elapsedMs + " ms", "ERROR"});
            return;
        }
        String type = parts[1], respDomain = parts[2];
        if ("RESPONSE".equals(type) && parts.length >= 4) {
            String ip     = parts[3];
            String source = (parts.length >= 5) ? parts[4] : "DNS_LOOKUP";
            String ttlOrCount = (parts.length >= 6) ? parts[5] : "60";

            lblResultIP.setText(ip);
            lblResultIP.setForeground(ACCENT_GREEN);

            // Check previous record BEFORE updating so we know the recent time it was used
            SearchRecord priorRec = MongoHistoryService.getInstance().getRecord(respDomain);
            String recentTimeUsed = (priorRec != null && priorRec.getLastSearched() != null)
                    ? MongoHistoryService.formatTimeAgo(priorRec.getLastSearched())
                    : "Never used before (1st search)";

            SearchRecord curRec = MongoHistoryService.getInstance().findAndRecordHit(respDomain);
            int sCount = curRec != null ? curRec.getSearchCount() : 1;
            String curDate = LocalDate.now().toString();

            if ("CACHE".equalsIgnoreCase(source)) {
                lblResultStatus.setText("  CACHE HIT  ");
                lblResultStatus.setBackground(ACCENT_GREEN);
                lblResultStatus.setForeground(BG_DARK);
                lblResultTime.setText("⚡ " + elapsedMs + " ms");
                lblResultTime.setForeground(ACCENT_GREEN);
                lblResultSource.setText("Recent used: " + recentTimeUsed + " | TTL: " + ttlOrCount + "s");
                log(clientDoc, "  >>> " + respDomain + "  ->  " + ip + "  [⚡ CACHE HIT | Searches: " + sCount + " | Recent Used: " + recentTimeUsed + " | " + elapsedMs + " ms]\n", ACCENT_GREEN);
                historyTableModel.insertRow(0, new Object[]{curDate + " " + ts, respDomain, ip, "⚡ CACHE", sCount + " searches", "CACHE HIT"});
            } else if ("MONGODB".equalsIgnoreCase(source)) {
                lblResultStatus.setText("  SEARCH HISTORY HIT  ");
                lblResultStatus.setBackground(ACCENT_PURPLE);
                lblResultStatus.setForeground(TEXT_PRIMARY);
                lblResultTime.setText("🍃 " + elapsedMs + " ms");
                lblResultTime.setForeground(ACCENT_PURPLE);
                lblResultSource.setText("Recent used: " + recentTimeUsed + " | Searches: " + sCount);
                log(clientDoc, "  >>> " + respDomain + "  ->  " + ip + "  [🍃 HISTORY HIT | Searches: " + sCount + " | Recent Used: " + recentTimeUsed + " | " + elapsedMs + " ms]\n", ACCENT_PURPLE);
                historyTableModel.insertRow(0, new Object[]{curDate + " " + ts, respDomain, ip, "HISTORY", sCount + " searches", "HISTORY HIT"});
            } else {
                // NEW SEARCH (from DNS LOOKUP)
                lblResultStatus.setText("  RESOLVED  ");
                lblResultStatus.setBackground(ACCENT_CYAN);
                lblResultStatus.setForeground(BG_DARK);
                lblResultTime.setText("🌐 " + elapsedMs + " ms");
                lblResultTime.setForeground(ACCENT_CYAN);
                lblResultSource.setText("Recent used: " + recentTimeUsed + " | Searches: " + sCount);
                log(clientDoc, "  >>> " + respDomain + "  ->  " + ip + "  [🌐 RESOLVED | Searches: " + sCount + " | Recent Used: " + recentTimeUsed + " | " + elapsedMs + " ms]\n", ACCENT_CYAN);
                historyTableModel.insertRow(0, new Object[]{curDate + " " + ts, respDomain, ip, "DNS LOOKUP", sCount + " searches", "RESOLVED"});
            }
            // -- Save every successful DNS lookup to dns_queries (non-blocking) --
            final String _saveIp = ip;
            new Thread(() -> {
                try { MongoHistoryService.getInstance().saveDnsQuery(respDomain, _saveIp, "DNS_CLIENT"); }
                catch (Exception ignored) {}
            }, "dns-query-save").start();
            loadDnsQueryHistory();
        } else if ("ERROR".equals(type)) {
            String reason = parts.length >= 4 ? parts[3] : "UNKNOWN";
            lblResultIP.setText("--"); lblResultIP.setForeground(ACCENT_RED);
            lblResultStatus.setText("  NOT FOUND  "); lblResultStatus.setBackground(ACCENT_RED); lblResultStatus.setForeground(TEXT_PRIMARY);
            lblResultTime.setText(elapsedMs + " ms");
            lblResultTime.setForeground(ACCENT_RED);
            lblResultSource.setText(reason);
            log(clientDoc, "  !!! Error : " + reason + " (" + elapsedMs + " ms)\n", ACCENT_RED);
            historyTableModel.insertRow(0, new Object[]{ts, respDomain, "--", "--", elapsedMs + " ms", "NOT FOUND"});
        }
    }

    /**
     * Loads search history records from MongoDB into the history table upon application startup.
     */
    private void loadSearchHistoryFromMongoDB() {
        MongoHistoryService mongo = MongoHistoryService.getInstance();
        SwingWorker<java.util.List<SearchRecord>, Void> loader = new SwingWorker<>() {
            @Override
            protected java.util.List<SearchRecord> doInBackground() {
                return mongo.getAllHistory();
            }

            @Override
            protected void done() {
                try {
                    java.util.List<SearchRecord> records = get();
                    if (records != null && !records.isEmpty()) {
                        historyTableModel.setRowCount(0);
                        for (SearchRecord r : records) {
                            String timeDisplay = r.getLastSearched();
                            if (timeDisplay != null && timeDisplay.length() > 19) {
                                timeDisplay = timeDisplay.substring(0, 19).replace("T", " ");
                            }
                            historyTableModel.addRow(new Object[]{
                                timeDisplay != null ? timeDisplay : "--",
                                r.getDomain(),
                                r.getIp(),
                                r.getSource(),
                                r.getSearchCount() + " searches",
                                "RECORDED"
                            });
                        }
                    }
                } catch (Exception ignored) {}
            }
        };
        loader.execute();
    }

    // ════════════════════════════════════════════════════════════════════════
    // RECORDS LOADER
    // ════════════════════════════════════════════════════════════════════════
    // DNS QUERY HISTORY TAB  (dns_queries collection)
    // ════════════════════════════════════════════════════════════════════════

    private JPanel buildQueryLogTab() {
        JPanel tab = new JPanel(new BorderLayout(8, 10));
        tab.setBackground(BG_DARK);
        tab.setBorder(new EmptyBorder(14, 16, 14, 16));
        JPanel hdr = new JPanel(new BorderLayout());
        hdr.setOpaque(false);
        hdr.add(accentHeader("  DNS QUERY HISTORY  (every lookup logged with date & time)", ACCENT_AMBER), BorderLayout.WEST);
        JButton refresh = ghostButton("Refresh History");
        refresh.addActionListener(e -> loadDnsQueryHistory());
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        btnPanel.setOpaque(false);
        btnPanel.add(refresh);
        hdr.add(btnPanel, BorderLayout.EAST);
        queryTableModel = new DefaultTableModel(
            new String[]{"Date", "Time", "Domain", "IP Address", "Source"}, 0) {
            public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable qt = styledTable(queryTableModel);
        qt.getColumnModel().getColumn(0).setPreferredWidth(90);
        qt.getColumnModel().getColumn(1).setPreferredWidth(80);
        qt.getColumnModel().getColumn(2).setPreferredWidth(180);
        qt.getColumnModel().getColumn(3).setPreferredWidth(130);
        qt.getColumnModel().getColumn(4).setPreferredWidth(100);
        qt.getColumnModel().getColumn(4).setCellRenderer(new DefaultTableCellRenderer() {
            public Component getTableCellRendererComponent(
                    JTable tbl, Object v, boolean sel, boolean foc, int r, int c) {
                super.getTableCellRendererComponent(tbl, v, sel, foc, r, c);
                String s = v == null ? "" : v.toString();
                setFont(new Font("Segoe UI", Font.BOLD, 11));
                if (s.contains("CACHE"))       setForeground(ACCENT_GREEN);
                else if (s.contains("CLIENT")) setForeground(ACCENT_CYAN);
                else                            setForeground(ACCENT_AMBER);
                setBackground(sel ? new Color(80,160,255,50) : r%2==0 ? BG_CARD : new Color(22,30,44));
                setBorder(new EmptyBorder(0, 6, 0, 6));
                return this;
            }
        });
        JPanel cardP = card(BORDER_BRIGHT);
        cardP.setLayout(new BorderLayout(0, 8));
        cardP.setBorder(cardBorder(ACCENT_AMBER));
        cardP.add(hdr,        BorderLayout.NORTH);
        cardP.add(scroll(qt), BorderLayout.CENTER);
        tab.add(cardP, BorderLayout.CENTER);
        loadDnsQueryHistory();
        return tab;
    }

    private void loadDnsQueryHistory() {
        if (queryTableModel == null) return;
        MongoHistoryService mongo = MongoHistoryService.getInstance();
        SwingWorker<java.util.List<DnsQueryRecord>, Void> worker = new SwingWorker<>() {
            @Override
            protected java.util.List<DnsQueryRecord> doInBackground() {
                return mongo.getRecentDnsQueries(200);
            }
            @Override
            protected void done() {
                try {
                    java.util.List<DnsQueryRecord> records = get();
                    if (records != null) {
                        queryTableModel.setRowCount(0);
                        for (DnsQueryRecord r : records) {
                            queryTableModel.addRow(new Object[]{
                                r.getDateDisplay(),
                                r.getTimeDisplay(),
                                r.getDomain(),
                                r.getIp(),
                                r.getSource()
                            });
                        }
                    }
                } catch (Exception ignored) {}
            }
        };
        worker.execute();
    }

    // ════════════════════════════════════════════════════════════════════════
    private Map<String,String> loadRecords(String file) {
        Map<String,String> m = new LinkedHashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 1) continue;
                String d  = line.substring(0, eq).trim().toLowerCase();
                String ip = line.substring(eq + 1).trim();
                if (!d.isEmpty() && !ip.isEmpty()) m.put(d, ip);
            }
            return m;
        } catch (IOException e) { return null; }
    }

    // ════════════════════════════════════════════════════════════════════════
    // LOG HELPERS
    // ════════════════════════════════════════════════════════════════════════
    private void log(StyledDocument doc, String text, Color color) {
        SwingUtilities.invokeLater(() -> {
            try {
                Style s = doc.addStyle("s", null);
                StyleConstants.setForeground(s, color);
                StyleConstants.setFontFamily(s, "Consolas");
                StyleConstants.setFontSize(s, 13);
                StyleConstants.setBold(s, false);
                doc.insertString(doc.getLength(), text, s);
                JTextPane pane = (doc == serverDoc) ? serverLogPane : clientLogPane;
                pane.setCaretPosition(doc.getLength());
            } catch (BadLocationException ignored) {}
        });
    }

    private void clearDoc(StyledDocument doc) {
        try { doc.remove(0, doc.getLength()); } catch (BadLocationException ignored) {}
    }
    // ════════════════════════════════════════════════════════════════════════
    // UI COMPONENT FACTORIES
    // ════════════════════════════════════════════════════════════════════════

    /** Dark card panel with a visible coloured border. */
    private JPanel card(Color borderCol) {
        JPanel p = new JPanel();
        p.setBackground(BG_CARD);
        p.setBorder(new LineBorder(borderCol, 1, true));
        return p;
    }

    /** Card border: coloured top-accent line + padding. */
    private Border cardBorder(Color accent) {
        return new CompoundBorder(
            new LineBorder(accent, 2, true),
            new EmptyBorder(10, 12, 10, 12));
    }

    /** Coloured header strip with bold white label. */
    private JPanel accentHeader(String text, Color accent) {
        JPanel strip = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 5));
        strip.setBackground(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 35));
        strip.setBorder(new MatteBorder(0, 3, 0, 0, accent));

        JLabel l = new JLabel(text);
        l.setFont(new Font("Segoe UI", Font.BOLD, 13));
        l.setForeground(accent);
        strip.add(l);
        return strip;
    }

    /** Bold coloured badge (opaque pill). */
    private JLabel badge(String text, Color bg) {
        JLabel l = new JLabel("  " + text + "  ");
        l.setFont(new Font("Segoe UI", Font.BOLD, 12));
        l.setForeground(Color.WHITE);
        l.setBackground(bg);
        l.setOpaque(true);
        l.setBorder(new EmptyBorder(4, 10, 4, 10));
        return l;
    }

    /** Larger status badge with bolder text. */
    private JLabel statusBadge(String text, Color bg) {
        JLabel l = new JLabel("  " + text + "  ");
        l.setFont(new Font("Segoe UI", Font.BOLD, 13));
        l.setForeground(TEXT_PRIMARY);
        l.setBackground(bg);
        l.setOpaque(true);
        l.setBorder(new EmptyBorder(5, 12, 5, 12));
        return l;
    }

    /** Rounded accent button with rollover brighten. */
    private JButton accentButton(String text, Color bg) {
        JButton b = new JButton(text) {
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getModel().isRollover() ? bg.brighter() : bg);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        b.setFont(new Font("Segoe UI", Font.BOLD, 13));
        b.setForeground(Color.WHITE);
        b.setBackground(bg);
        b.setContentAreaFilled(false);
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setBorder(new EmptyBorder(10, 20, 10, 20));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    /** Ghost button — visible border, dim text, brightens on hover. */
    private JButton ghostButton(String text) {
        JButton b = new JButton(text);
        b.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        b.setForeground(TEXT_MUTED);
        b.setBackground(BG_CARD);
        b.setContentAreaFilled(false);
        b.setFocusPainted(false);
        b.setBorder(new CompoundBorder(
            new LineBorder(BORDER_BRIGHT, 1, true),
            new EmptyBorder(6, 14, 6, 14)));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) { b.setForeground(TEXT_PRIMARY); }
            public void mouseExited(MouseEvent e)  { b.setForeground(TEXT_MUTED); }
        });
        return b;
    }

    /** Coloured domain chip with per-colour border. */
    private JButton chipButton(String text, Color accent) {
        JButton b = new JButton(text);
        b.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        b.setForeground(accent);
        b.setBackground(BG_CARD);
        b.setContentAreaFilled(false);
        b.setFocusPainted(false);
        b.setBorder(new CompoundBorder(
            new LineBorder(accent, 1, true),
            new EmptyBorder(3, 10, 3, 10)));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) {
                b.setForeground(BG_DARK);
                b.setBackground(accent);
                b.setContentAreaFilled(true);
                b.repaint();
            }
            public void mouseExited(MouseEvent e) {
                b.setForeground(accent);
                b.setBackground(BG_CARD);
                b.setContentAreaFilled(false);
                b.repaint();
            }
        });
        return b;
    }

    private JLabel mkLabel(String text, Color fg, Font font) {
        JLabel l = new JLabel(text);
        l.setForeground(fg);
        l.setFont(font);
        return l;
    }

    /** Dark monospace log pane with slightly lighter background. */
    private JTextPane logPane() {
        JTextPane p = new JTextPane();
        p.setEditable(false);
        p.setBackground(new Color(12, 16, 24));
        p.setForeground(TEXT_PRIMARY);
        p.setFont(new Font("Consolas", Font.PLAIN, 13));
        return p;
    }

    private JTable styledTable(DefaultTableModel model) {
        JTable t = new JTable(model);
        t.setBackground(BG_CARD);
        t.setForeground(TEXT_PRIMARY);
        t.setFont(FONT_BODY);
        t.setRowHeight(28);
        t.setShowGrid(false);
        t.setIntercellSpacing(new Dimension(0, 1));
        t.setSelectionBackground(new Color(80, 160, 255, 70));
        t.setSelectionForeground(TEXT_PRIMARY);

        JTableHeader th = t.getTableHeader();
        th.setBackground(new Color(25, 32, 48));
        th.setForeground(ACCENT_CYAN);
        th.setFont(new Font("Segoe UI", Font.BOLD, 12));
        th.setReorderingAllowed(false);
        th.setBorder(new MatteBorder(0, 0, 2, 0, BORDER_BRIGHT));
        th.setPreferredSize(new Dimension(0, 32));

        t.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            public Component getTableCellRendererComponent(
                    JTable tbl, Object v, boolean sel, boolean foc, int r, int c) {
                super.getTableCellRendererComponent(tbl, v, sel, foc, r, c);
                setForeground(TEXT_PRIMARY);
                setFont(FONT_BODY);
                setBorder(new EmptyBorder(0, 10, 0, 10));
                setBackground(sel ? new Color(80, 160, 255, 70)
                                  : r%2==0 ? BG_CARD : new Color(22, 30, 44));
                return this;
            }
        });
        return t;
    }

    private JScrollPane scroll(Component c) {
        JScrollPane sp = new JScrollPane(c);
        sp.setBorder(new LineBorder(BORDER_BRIGHT, 1));
        sp.getViewport().setBackground(BG_CARD);
        sp.getVerticalScrollBar().setBackground(BG_PANEL);
        return sp;
    }
}