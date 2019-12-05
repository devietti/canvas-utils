package canvas;

import canvas.apiobjects.Group;
import canvas.apiobjects.Profile;
import canvas.apiobjects.User;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpResponse;

import javax.json.*;
import javax.json.stream.JsonGenerator;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Create a JSON file containing the mapping group => [(student,email), ...]
 */
public class CreateGroupDB {

    public static void main(String[] args) throws IOException {

        // read command-line args
        if (args.length < 1 || args.length > 2) {
            System.out.println("Usage: " + CreateGroupDB.class.getName() + " <group-db-file.json> [group name filter]");
            return;
        }
        final String JSON_FILE = args[0];
        PrintWriter w = new PrintWriter(new FileWriter(JSON_FILE));
        final String groupNameFilter = (2 == args.length) ? args[1] : "";
        if (!groupNameFilter.equals("")) {
            System.out.format("Using group name filter: '%s'%n", groupNameFilter);
        }

        // populate list of groups
        System.out.println("Getting list of groups...");

        Common.setup();

        // groupDB schema: group name => [all,group,members,...]
        JsonObjectBuilder jobOuter = Json.createObjectBuilder();

        List<Group> groups = Common.getAsList("groups", Group[].class);
        for (Group g : groups) {
            if (!g.name.toLowerCase().contains(groupNameFilter)) {
                continue;
            }

            List<User> groupMembers = Common.getAsList(new GenericUrl(Common.BASE_URL + "groups/" + g.id + "/users"), User[].class);
            if (0 == groupMembers.size()) { // skip empty groups
                continue;
            }

            JsonArrayBuilder jabG = Json.createArrayBuilder();
            for (User gmem : groupMembers) {
                // NB: have to get student's email via their Profile
                Profile prof = Common.getAs(new GenericUrl(Common.BASE_URL + "users/" + gmem.id + "/profile"),
                        Profile.class);
                String email = (null != prof.primary_email) ? prof.primary_email : gmem.email;
                if (null == email) {
                    email = gmem.login_id+"@seas.upenn.edu";
                    System.out.format("No email address for %s (%d %s). Using %s instead %n",
                            gmem.name, gmem.id, gmem.login_id, email);
                }

                JsonObject jo = Json.createObjectBuilder().add("name", gmem.name)
                        .add("email", email)
                        .add("id", gmem.id).build();
                jabG.add(jo);
            }
            jobOuter.add(String.valueOf(g.name), jabG);
        }

        Map<String, Object> properties = new HashMap<>(1);
        properties.put(JsonGenerator.PRETTY_PRINTING, true);
        JsonWriterFactory writerFactory = Json.createWriterFactory(properties);
        JsonWriter jsonWriter = writerFactory.createWriter(w);
        jsonWriter.writeObject(jobOuter.build());
        jsonWriter.close();
        w.close();
    }

}
