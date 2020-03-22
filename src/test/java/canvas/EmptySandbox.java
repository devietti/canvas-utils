package canvas;

import canvas.apiobjects.Assignment;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponse;

import java.io.IOException;
import java.util.List;

import static canvas.Common.*;
import static org.junit.Assert.assertEquals;

public class EmptySandbox {
    public static void main(String[] args) throws IOException {
        Common.setup();
        Common.useSandboxSite();

        // delete all existing assignments
        System.out.println("* deleting existing sandbox Assignments...");
        List<Assignment> SandboxAsns = Common.getAsList("assignments", Assignment[].class);
        for (Assignment asn : SandboxAsns) {
            GenericUrl url = new GenericUrl(Common.CourseURL() + "assignments/" + asn.id);
            HttpRequest request = requestFactory.buildDeleteRequest(url);
            request.getHeaders().setAuthorization(TOKEN);
            HttpResponse response = request.execute();
            assertEquals(200, response.getStatusCode());
        }
    }
}
