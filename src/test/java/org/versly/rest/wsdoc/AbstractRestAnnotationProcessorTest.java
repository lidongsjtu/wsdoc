package org.versly.rest.wsdoc;

import freemarker.template.TemplateException;

import org.raml.model.Action;
import org.raml.model.ActionType;
import org.raml.model.Raml;
import org.raml.model.Resource;
import org.raml.model.parameter.QueryParameter;
import org.raml.model.parameter.UriParameter;
import org.raml.parser.visitor.RamlDocumentBuilder;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;
import org.versly.rest.wsdoc.impl.RestDocumentation;
import org.versly.rest.wsdoc.impl.Utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

public abstract class AbstractRestAnnotationProcessorTest {
    protected static String output;
    private static File tmpDir;
    protected static final String[] _outputFormats = { "html", "raml" };

    public void setUp() throws IOException, URISyntaxException, ClassNotFoundException, TemplateException {
        File tempFile = File.createTempFile("wsdoc", "tmp");
        tempFile.deleteOnExit();
        tmpDir = new File(tempFile.getParentFile(), "wsdoc-" + System.currentTimeMillis());
        tmpDir.mkdirs();
        tmpDir.deleteOnExit();
        for (String format: _outputFormats) {
            processResource("RestDocEndpoint.java", format, DocumentationScope.PUBLIC);
        }
    }

    protected void processResource(String fileName, String outputFormat, String scope) {
        processResource(fileName, outputFormat, null, scope, true);
    }

    private void processResource(
            String fileName, String outputFormat, Iterable<Pattern> excludes, String scope, boolean needsTestPackage) {
        try {
            runAnnotationProcessor(tmpDir, fileName, needsTestPackage);
            String outputFile = tmpDir + "/" + fileName.replace(".java", "." + outputFormat);
            buildOutput(tmpDir, outputFile, outputFormat, excludes, scope);
            readOutput(outputFile);
        } catch (Exception ex) {
            if (ex instanceof RuntimeException)
                throw (RuntimeException) ex;
            else
                throw new RuntimeException(ex);
        }
    }

    private static void buildOutput(
            File buildDir, String outputFile, String outputFormat, Iterable<Pattern> excludes, String scope)
        throws ClassNotFoundException, IOException, TemplateException {

        InputStream in = new FileInputStream(new File(buildDir, Utils.SERIALIZED_RESOURCE_LOCATION));

        // make the parent dirs in case htmlFile is nested
        new File(outputFile).getParentFile().mkdirs();

        new RestDocAssembler(outputFile, outputFormat).writeDocumentation(
                Collections.singletonList(RestDocumentation.fromStream(in)), excludes, scope);
    }

    private static void readOutput(String htmlFile) throws IOException {
        InputStream in = new FileInputStream(htmlFile);
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        output = "";
        for (String line = null; (line = reader.readLine()) != null; ) {
            output += line + "\n";
        }
    }

    private void runAnnotationProcessor(File buildDir, final String fileName, boolean needsTestPackage)
            throws URISyntaxException, IOException {

        String packagePrefix = "org/versly/rest/wsdoc/" + (needsTestPackage ? getPackageToTest() + "/" : "");
        runAnnotationProcessor(buildDir, packagePrefix, fileName);
    }

    protected static void runAnnotationProcessor(File buildDir, final String packagePrefix, final String fileName)
            throws URISyntaxException, IOException {
        AnnotationProcessor processor = new AnnotationProcessor();

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
        fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singletonList(buildDir));
        JavaFileObject file = new SimpleJavaFileObject(new URI("string:///" + packagePrefix + fileName),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean b) throws IOException {
                InputStream stream = getClass().getClassLoader().getResource(packagePrefix + fileName).openStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
                String str = "";
                for (String line = null; (line = reader.readLine()) != null; str += line + "\n")
                    ;
                return str;
            }
        };
        Collection<JavaFileObject> files = Collections.singleton(file);
        JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, null, null, null, files);
        task.setProcessors(Collections.singleton(processor));
        AssertJUnit.assertTrue(task.call());
    }

    @Test
    public void assertJavaDocComments() {
        for (String format: _outputFormats) {
            processResource("RestDocEndpoint.java", format, "public");
            AssertJUnit.assertTrue(
                    "expected 'JavaDoc comment' in doc string; got: \n" + output,
                    output.contains("JavaDoc comment"));
        }
    }

    @Test
    public void assertReturnValueComments() {
        processResource("RestDocEndpoint.java", "html", "public");
        AssertJUnit.assertTrue("expected \"exciting return value's date\" in doc string; got: \n" + output,
                output.contains("exciting return value's date"));
    }

    @Test
    public void assertPathVariableWithOverriddenName() {
        for (String format: _outputFormats) {
            processResource("RestDocEndpoint.java", format, "public");
            AssertJUnit.assertTrue("expected \"dateParam\" in doc string; got: \n" + output,
                    output.contains("dateParam"));
        }
    }

    @Test
    public void assertParams() {
        processResource("RestDocEndpoint.java", "html", "public");
        AssertJUnit.assertTrue("expected param0 and param1 in docs; got: \n" + output,
                output.contains(">param0<") && output.contains(">param1<"));
    }

    @Test
    public void assertOverriddenPaths() {
        processResource("RestDocEndpoint.java", "html", "public");
        AssertJUnit.assertTrue("expected multiple voidreturn sections; got: \n" + output,
                output.indexOf("<a id=\"/mount/api/v1/voidreturn") != output.lastIndexOf("<a id=\"/mount/api/v1/voidreturn"));
    }

    @Test
    public void assertUuidIsNotTraversedInto() {
        processResource("RestDocEndpoint.java", "html", "public");
        AssertJUnit.assertFalse(
                "leastSignificantBits field (member field of UUID class) should not be in output",
                output.contains("leastSignificantBits"));
        AssertJUnit.assertTrue("expected uuid type somewhere in doc",
                output.contains("json-primitive-type\">uuid<"));
    }

    @Test
    public void generateExample() {
        for (String format: _outputFormats) {
            processResource("SnowReportController.java", format, "public");
        }
    }

    @Test
    public void nonRecursiveTypeWithMultipleUsesDoesNotHaveRecursionCircles() {
        for (String format: _outputFormats) {
            processResource("NonRecursiveMultiUse.java", format, "public");
            AssertJUnit.assertFalse("should not contain the recursion symbol",
                    output.contains("&#x21ba;"));
        }
    }

    @Test
    public void assertAllMethods() {
        for (String format : _outputFormats) {
            processResource("AllMethods.java", format, "public");
            AssertJUnit.assertTrue(
                    "expected 'allMethodsGet' in doc string; got: \n" + output,
                    output.contains("allMethodsGet"));
            AssertJUnit.assertTrue(
                    "expected 'allMethodsPost' in doc string; got: \n" + output,
                    output.contains("allMethodsPost"));
            AssertJUnit.assertTrue(
                    "expected 'allMethodsPut' in doc string; got: \n" + output,
                    output.contains("allMethodsPut"));
            AssertJUnit.assertTrue(
                    "expected 'allMethodsDelete' in doc string; got: \n" + output,
                    output.contains("allMethodsDelete"));
        }
    }

    @Test
    public void excludePatterns() {
        for (String format: _outputFormats) {
            processResource("SnowReportController.java", format,
                    Arrays.asList(Pattern.compile("foo"), Pattern.compile(".*snow-report.*")), "public", true);
            AssertJUnit.assertFalse("should not contain the snow-report endpoint",
                    output.contains("snow-report"));
        }
    }

    @Test
    public void genericTypeResolution() throws IOException, URISyntaxException {
        for (String format: _outputFormats) {
            processResource("RestDocEndpoint.java", format, "public");
        }
    }

    // issue #29
    @Test
    public void assertNoRedundantUriParametersForResource() {
        processResource("RestDocEndpoint.java", "raml", "public");
        Raml raml = new RamlDocumentBuilder().build(output, "http://example.com");
        AssertJUnit.assertNotNull("RAML not parseable", raml);
        Resource resource = raml.getResource("/mount/api/v1/widgets/{id1}/gizmos");
        AssertJUnit.assertNotNull("Resource /mount/api/v1/widgets/{id1}/gizmos not found", resource);
        resource = resource.getResource("/{id2}");
        AssertJUnit.assertNotNull("Resource /mount/api/v1/widgets/{id1}/gizmos/{id2} not found", resource);
    }

    @Test
    public void assertUriParameterNormalization() {
        processResource("UriParameterNormalization.java", "raml", "public");
        Raml raml = new RamlDocumentBuilder().build(output, "http://example.com");
        AssertJUnit.assertNotNull("RAML not parseable", raml);
        Resource resource = raml.getResource("/widgets/{id}");
        AssertJUnit.assertNotNull("Resource /widgets/{id} not found", resource);
        UriParameter id = resource.getUriParameters().get("id");
        AssertJUnit.assertNotNull("Resource /widgets/{id} has no id URI parameter", id);
        AssertJUnit.assertEquals("Resource /widgets/{id} id URI parameter description is wrong",
                "The widget identifier.", id.getDescription().trim());
        resource = resource.getResource("/gadgets");
        AssertJUnit.assertNotNull("Resource /widgets/{id}/gadgets not found", resource);
        id = resource.getUriParameters().get("id");
        AssertJUnit.assertNull("Resource /widgets/{id}/gadgets has it's own id URI parameter when it should not", id);
    }

    @Test
    public void testEnumsTypesQueryForRaml() {
        processResource("RestDocEndpoint.java", "raml", "public");
        Raml raml = new RamlDocumentBuilder().build(output, "http://example.com");
        Resource resource = raml.getResource("/mount/api/v1/whirlygigs");
        AssertJUnit.assertNotNull("Resource /mount/api/v1/whirlygigs not found", resource);
        Action action = resource.getAction(ActionType.GET);
        AssertJUnit.assertNotNull("Method GET not found on /mount/api/v1/whirlygigs", action);
        QueryParameter qp = action.getQueryParameters().get("color");
        AssertJUnit.assertNotNull("No color query param found on GET method of /mount/api/v1/whirlygigs", qp);
        List<String> enums = qp.getEnumeration();
        AssertJUnit.assertNotNull("Color query param on GET method of /mount/api/v1/whirlygigs not enum", enums);
        AssertJUnit.assertEquals("Color query param on GET /mount/api/v1/whirlygigs is wrong size", 3, enums.size());
    }

    @Test
    public void testEnumsTypesInPathForRaml() {
        processResource("RestDocEndpoint.java", "raml", "public");
        Raml raml = new RamlDocumentBuilder().build(output, "http://example.com");
        Resource resource = raml.getResource("/mount/api/v1/colors/{color}");
        AssertJUnit.assertNotNull("Resource /mount/api/v1/colors/{color} not found", resource);
        UriParameter up = resource.getUriParameters().get("color");
        AssertJUnit.assertNotNull("No color path param found on GET method of /mount/api/v1/colors/{color}", up);
        List<String> enums = up.getEnumeration();
        AssertJUnit.assertNotNull("Color path param on GET method of /mount/api/v1/colors/{color} not enum", enums);
        AssertJUnit.assertEquals("Color path param on GET /mount/api/v1/colors/{color} is wrong size", 3, enums.size());
    }
    
    @Test
    public void testPublicationScopes() {
        for (String format : _outputFormats) {
            processResource("PublicationScopes.java", format, "public");
            AssertJUnit.assertTrue("expected 'public1' in doc string; got: \n" + output, output.contains("public1"));
            AssertJUnit.assertTrue("expected 'public2' in doc string; got: \n" + output, output.contains("public2"));
            AssertJUnit.assertTrue("expected 'public4' in doc string; got: \n" + output, output.contains("public4"));
            AssertJUnit.assertTrue("expected 'public5' in doc string; got: \n" + output, output.contains("public5"));
            AssertJUnit.assertTrue("expected no 'private' in doc string; got: \n" + output, !output.contains("private"));

            processResource("PublicationScopes.java", format, "all");
            AssertJUnit.assertTrue("expected 'public1' in doc string; got: \n" + output, output.contains("public1"));
            AssertJUnit.assertTrue("expected 'public2' in doc string; got: \n" + output, output.contains("public2"));
            AssertJUnit.assertTrue("expected 'public4' in doc string; got: \n" + output, output.contains("public4"));
            AssertJUnit.assertTrue("expected 'public5' in doc string; got: \n" + output, output.contains("public5"));
            AssertJUnit.assertTrue("expected 'private2' in doc string; got: \n" + output, output.contains("private2"));
            AssertJUnit.assertTrue("expected 'private3' in doc string; got: \n" + output, output.contains("private3"));
            AssertJUnit.assertTrue("expected 'private4' in doc string; got: \n" + output, output.contains("private4"));
        }
    }

    protected abstract String getPackageToTest();
}
