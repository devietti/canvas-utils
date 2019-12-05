package canvas.apiobjects;

import com.google.api.client.util.Key;

public class User {
    @Key
    public int id;
    @Key
    public String name;
    @Key
    public String email; // NB: this is always empty at Penn, but the Profile has a valid email
    @Key
    public String sis_user_id;
    @Key
    public String login_id; // NB: PennKey

    public Group group;

    /**
     * NB: use structural equality as there are potentially multiple identical instances of each
     * User
     */
    @Override
    public boolean equals(Object o) {
        if (o instanceof User) {
            User u = (User) o;
            return u.id == this.id && u.name.equals(this.name);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return id;
    }

    @Override
    public String toString() {
        return name;
    }
}
