package au.org.emii.ncdfgenerator;

import au.org.emii.ncdfgenerator.cql.*;
import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.postgresql.copy.CopyManager;
import org.postgresql.util.PSQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.assertEquals;

class MockOutputterCounter implements IOutputFormatter {
    int count;

    public final void prepare(OutputStream os) {
        this.count = 0;
    }

    public final void write(String filename, InputStream is) {
        ++count;
    }

    public final void finish() {
    }

    public int getCount() {
        return count;
    }
};

public class GenerationIT {

    static final String TMPDIR = "./tmp";
    private static final Logger logger = LoggerFactory.getLogger(GenerationIT.class);

    private static boolean dbIsPrepared = false;

    public GenerationIT() throws Exception {
        if (!dbIsPrepared) {
            logger.info("Prepare db");

            InputStream is = null;
            Connection conn = null;
            try {
                conn = getConn();

                is = getClass().getResourceAsStream("/scripts/anmn_ts.sql");
                conn.prepareStatement("drop schema if exists anmn_ts cascade").execute();
                importSQL(conn, is);

                is = getClass().getResourceAsStream("/scripts/anmn_nrs_ctd_profiles.sql");
                conn.prepareStatement("drop schema if exists anmn_nrs_ctd_profiles cascade").execute();
                importSQL(conn, is);

                is = getClass().getResourceAsStream("/scripts/soop_sst.sql");
                conn.prepareStatement("drop schema if exists soop_sst cascade").execute();
                importSQL(conn, is);
            }
            finally {
                if (is != null) {
                    is.close();
                }
                if (conn != null) {
                    conn.close();
                }
            }
            dbIsPrepared = true;
        }
    }

    private static void importSQL(Connection conn, InputStream is) throws Exception {

        // Supports Postgres data copy actions over jdbc
        final String regularStmts[] = {"SET", "CREATE", "COMMENT", "ALTER", "SELECT"};
        Statement st = conn.createStatement();
        CopyManager copyManager = ((PGConnection)conn).getCopyAPI();

        logger.info("Importing SQL");

        int ch = -1;
        while ((ch = is.read()) >= 0) {
            StringBuilder sb = new StringBuilder();

            // glob firstToken
            while (ch >= 0 && ch != ' ' && ch != '\n') {
                sb.append((char)ch);
                ch = is.read();
            }

            String firstTok = sb.toString();

            // empty line
            if (firstTok.equals("")) {
                continue;
            }
            // comment
            if (firstTok.equals("--")) {
                while (ch >= 0 && ch != '\n') {
                    ch = is.read();
                }
                continue;
            }
            // glob rest of the stmt
            while (ch >= 0 && ch != ';') {
                if (ch >= 0) {
                    sb.append((char)ch);
                }
                ch = is.read();
            }

            // sb.append((char)ch); // ;
            String sql = sb.toString();

            // statement type
            boolean regularStmt = false;
            for (String stmt : regularStmts) {
                if (stmt.equals(firstTok)) {
                    regularStmt = true;
                }
            }

            if (regularStmt) {
                logger.debug("query " + sql);
                st.execute(sql);
            }
            else if (firstTok.equals("COPY")) {
                logger.debug("Copying data");
                is.read(); // \n

                // the copyManager reads to eof and doesn't respect the \n\\. terminating sequence. so we have
                // to scan ourselves
                CopyIn copyIn = copyManager.copyIn(sql);
                byte[] b = new byte[3];

                is.mark(3);
                is.read(b, 0, 3);
                while (!(b[0] == '\n' && b[1] == '\\' && b[2] == '.')) {
                    copyIn.writeToCopy(b, 0, 1);
                    is.reset();
                    is.read(); // advance one byte
                    is.mark(3);
                    is.read(b, 0, 3);
                }
                copyIn.endCopy();
            }
            else {
                throw new RuntimeException("Unknown SQL token during import '" + firstTok + "'");
            }
        }
    }

    private Connection getConn() throws Exception {
        Map<String, String> env = System.getenv();

        String opts[] = {"POSTGRES_USER", "POSTGRES_PASS", "POSTGRES_JDBC_URL"};
        for (String opt : opts) {
            if (env.get(opt) == null) {
                throw new Exception("Environment var '" + opt + "' not set");
            }
        }

        Properties props = new Properties();
        props.setProperty("user", env.get("POSTGRES_USER"));
        props.setProperty("password", env.get("POSTGRES_PASS"));
        props.setProperty("ssl", "true");
        props.setProperty("sslfactory", "org.postgresql.ssl.NonValidatingFactory");
        props.setProperty("driver", "org.postgresql.Driver");

        return DriverManager.getConnection(env.get("POSTGRES_JDBC_URL"), props);
    }

    private NcdfEncoder getEncoder(InputStream config, String filterExpr, Connection conn, IOutputFormatter outputGenerator) throws Exception {

        // we can't use the builder for this, because config is a stream...

        IExprParser parser = new ExprParser();
        IDialectTranslate translate = new PGDialectTranslate();
        ICreateWritable createWritable = new CreateWritable(TMPDIR);
        IAttributeValueParser attributeValueParser = new AttributeValueParser();

        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(config);
        Node node = document.getFirstChild();
        NcdfDefinition definition = new NcdfDefinitionXMLParser().parse(node);

        return new NcdfEncoder(parser, translate, conn, createWritable, attributeValueParser, definition, filterExpr, outputGenerator, System.out);
    }

    private InputStream getAnmnConfig() {
        return getClass().getResourceAsStream("/anmn_ts.xml");
    }

    @BeforeClass
    public static void beforeClass() {
        // called before constructor and must be static...
    }

    @Before
    public void setup() throws Exception {
        logger.info("delete and create TMPDIR");
        File file = new File(TMPDIR);
        FileUtils.deleteDirectory(file);
        file.mkdirs();
    }

    @Test
    public void testNothing() throws Exception {
        // support devel testing constructor setup
    }

    @Test
    public void testAnmnNrsCtdProfiles() throws Exception {
        String layerConfigDir = getClass().getResource("/").getFile();
        String tmpCreationDir = TMPDIR;
        NcdfGenerator generator = new NcdfGenerator(layerConfigDir, tmpCreationDir);
        OutputStream os = new FileOutputStream(TMPDIR + "/output.zip");
        String cql = "TIME < '2013-6-29T00:40:01Z' ";
        generator.write("anmn_nrs_ctd_profiles", cql, getConn(), os);
    }

    @Test
    public void testSoopSSTTrajectory() throws Exception {
        String layerConfigDir = getClass().getResource("/").getFile();
        String tmpCreationDir = TMPDIR;
        NcdfGenerator generator = new NcdfGenerator(layerConfigDir, tmpCreationDir);
        OutputStream os = new FileOutputStream(TMPDIR + "/output.zip");
        String cql = "TIME >= '2013-6-27T00:35:01Z' AND TIME <= '2013-6-29T00:40:01Z' ";
        generator.write("soop_sst_trajectory", cql, getConn(), os);
    }

    @Test
    public void testAnmnTs() throws Exception {
        String layerConfigDir = getClass().getResource("/").getFile();
        String tmpCreationDir = TMPDIR;
        NcdfGenerator generator = new NcdfGenerator(layerConfigDir, tmpCreationDir);
        OutputStream os = new FileOutputStream(TMPDIR + "/output.zip");
        String cql = "INTERSECTS(geom,POLYGON((113.3349609375 -33.091796875,113.3349609375 -30.982421875,117.1142578125 -30.982421875,117.1142578125 -33.091796875,113.3349609375 -33.091796875))) AND TIME >= '2015-01-13T23:00:00Z' AND TIME <= '2015-04-14T00:00:00Z' ";
        generator.write("anmn_ts", cql, getConn(), os);
    }

    @Test
    public void testCqlWithValidSpatialTemporalSubset() throws Exception {
        String cql = "INTERSECTS(geom,POLYGON((113.3349609375 -33.091796875,113.3349609375 -30.982421875,117.1142578125 -30.982421875,117.1142578125 -33.091796875,113.3349609375 -33.091796875))) AND TIME >= '2015-01-13T23:00:00Z' AND TIME <= '2015-04-14T00:00:00Z'";
        MockOutputterCounter outputter = new MockOutputterCounter();
        NcdfEncoder encoder = getEncoder(getAnmnConfig(), cql, getConn(), outputter);
        encoder.write();
        assertEquals(11, outputter.getCount());
    }

    @Test
    public void testCqlWithNoDataInSpatialSubset() throws Exception {
        String cql = "INTERSECTS(geom,POLYGON((163.7841796875 -15.9970703125,163.7841796875 -3.0771484375,173.8037109375 -3.077148437499999,173.8037109375 -15.9970703125,163.7841796875 -15.9970703125))) AND TIME >= '2015-01-13T23:00:00Z' AND TIME <= '2015-04-14T00:00:00Z'";
        MockOutputterCounter outputter = new MockOutputterCounter();
        NcdfEncoder encoder = getEncoder(getAnmnConfig(), cql, getConn(), outputter);
        encoder.write();
        assertEquals(0, outputter.getCount());
    }

    @Test
    public void testCqlWithTemporalExtentOutOfAllowedRange() throws Exception {

        String cql = "INTERSECTS(geom,POLYGON((113.33 -33.09,113.33 -30.98,117.11 -30.98,117.11 -33.09,113.33 -33.09))) AND TIME >= '1949-01-01T23:00:00Z' AND TIME <= '1951-01-01T00:00:00Z'";
        MockOutputterCounter outputter = new MockOutputterCounter();
        NcdfEncoder encoder = getEncoder(getAnmnConfig(), cql, getConn(), outputter);
        encoder.write();
        assertEquals(0, outputter.getCount());
    }

    @Test(expected = PSQLException.class)
    public void testCqlLongitudeOutsideAllowedRange() throws Exception {

        String cql = "INTERSECTS(geom,POLYGON((182 -33.09,113.33 -30.98,117.11 -30.98,117.11 -33.09,113.33 -33.09))) AND TIME >= '2015-01-13T23:00:00Z' AND TIME <= '2015-04-14T00:00:00Z'";

        NcdfEncoder encoder = getEncoder(getAnmnConfig(), cql, getConn(), new MockOutputterCounter());
        encoder.write();
    }

    @Test(expected = PSQLException.class)
    public void testCqlLatitudeOutsideAllowedRange() throws Exception {

        String cql = "INTERSECTS(geom,POLYGON((113.33 -95,113.33 -30.98,117.11 -30.98,117.11 -33.09,113.33 -33.09))) AND TIME >= '2015-01-13T23:00:00Z' AND TIME <= '2015-04-14T00:00:00Z'";

        NcdfEncoder encoder = getEncoder(getAnmnConfig(), cql, getConn(), new MockOutputterCounter());
        encoder.write();
    }

    @Test
    public void testCqlWithFloatEqualityValid() throws Exception {
        String cql = "INTERSECTS(geom,POLYGON((113.3349609375 -33.091796875,113.3349609375 -30.982421875,117.1142578125 -30.982421875,117.1142578125 -33.091796875,113.3349609375 -33.091796875))) AND TIME >= '2015-01-13T23:00:00Z' AND TIME <= '2015-04-14T00:00:00Z' AND (NOMINAL_DEPTH = 125.0 OR NOMINAL_DEPTH = 150.0)";
        MockOutputterCounter outputter = new MockOutputterCounter();
        NcdfEncoder encoder = getEncoder(getAnmnConfig(), cql, getConn(), outputter);
        encoder.write();
        assertEquals(2, outputter.getCount());
    }

    @Test(expected = CQLException.class)
    public void testCqlWithFloatEqualityInvalid() throws Exception {
        // eg. 5000. is not valid cql float
        String cql = "INTERSECTS(geom,POLYGON((113.3349609375 -33.091796875,113.3349609375 -30.982421875,117.1142578125 -30.982421875,117.1142578125 -33.091796875,113.3349609375 -33.091796875))) AND TIME >= '2015-01-13T23:00:00Z' AND TIME <= '2015-04-14T00:00:00Z' AND NOMINAL_DEPTH = 5000.";
        NcdfEncoder encoder = getEncoder(getAnmnConfig(), cql, getConn(), new MockOutputterCounter());
        encoder.write();
    }

    @Test
    public void testCqlWithFloatInequalityValid() throws Exception {
        String cql = "INTERSECTS(geom,POLYGON((113.3349609375 -33.091796875,113.3349609375 -30.982421875,117.1142578125 -30.982421875,117.1142578125 -33.091796875,113.3349609375 -33.091796875))) AND TIME >= '2015-01-13T23:00:00Z' AND TIME <= '2015-04-14T00:00:00Z' AND NOMINAL_DEPTH <> 125.0";
        MockOutputterCounter outputter = new MockOutputterCounter();
        NcdfEncoder encoder = getEncoder(getAnmnConfig(), cql, getConn(), outputter);
        encoder.write();
        assertEquals(10, outputter.getCount());
    }

    @Test
    public void testCqlWithStringEqualityValid() throws Exception {
        // QC flag is represented as a string in the db, so must quote
        String cql = "INTERSECTS(geom,POLYGON((113.3349609375 -33.091796875,113.3349609375 -30.982421875,117.1142578125 -30.982421875,117.1142578125 -33.091796875,113.3349609375 -33.091796875))) AND TIME >= '2015-01-13T23:00:00Z' AND TIME <= '2015-04-14T00:00:00Z' AND TEMP_quality_control = '4'";
        MockOutputterCounter outputter = new MockOutputterCounter();
        NcdfEncoder encoder = getEncoder(getAnmnConfig(), cql, getConn(), outputter);
        encoder.write();
        assertEquals(10, outputter.getCount());
        // TODO, should check that we only include obs with temp_qc = 4 etc, not just that instances are constrained.
    }
}

