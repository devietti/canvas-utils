package canvas.apiobjects;

import com.google.api.client.util.Key;

public class Group {
    @Key
    public Integer id;
    @Key
    public String name;
    public User[] members;

    /**
     * NB: use structural equality as there are potentially multiple identical instances of each
     * Group
     */
    @Override
    public boolean equals(Object o) {
        if (o instanceof Group) {
            Group g = (Group) o;
            return g.id.equals(this.id) && g.name.equals(this.name);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return id;
    }

    @Override
    public String toString() {
        return String.format("Group{ id: %d, name: '%s'}", id, name);
    }
}
