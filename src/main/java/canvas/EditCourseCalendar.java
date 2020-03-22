package canvas;

import canvas.apiobjects.Assignment;
import canvas.apiobjects.CalendarEvent;
import com.google.api.client.http.GenericUrl;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EditCourseCalendar {

    // current semester information
    private static final int THE_YEAR = 2020;
    private static final LocalDate FIRST_DAY_OF_CLASSES = LocalDate.of(THE_YEAR, Month.JANUARY, 15);
    private static final LocalDate LAST_DAY_OF_CLASSES = LocalDate.of(THE_YEAR, Month.APRIL, 29);
    private static final LocalDate LAST_DAY_OF_SEMESTER = LocalDate.of(THE_YEAR, Month.MAY, 12);
    private static final LocalDate[] BREAKS = new LocalDate[]{
            LocalDate.of(THE_YEAR, Month.MARCH, 9), // Spring Break
            LocalDate.of(THE_YEAR, Month.MARCH, 10), // Spring Break
            LocalDate.of(THE_YEAR, Month.MARCH, 11), // Spring Break
            LocalDate.of(THE_YEAR, Month.MARCH, 12), // Spring Break
            LocalDate.of(THE_YEAR, Month.MARCH, 13), // Spring Break
            /*
            LocalDate.of(THE_YEAR, Month.SEPTEMBER, 2), // Labor Day
            LocalDate.of(THE_YEAR, Month.OCTOBER, 10), // Fall Break
            LocalDate.of(THE_YEAR, Month.OCTOBER, 11), // Fall Break
            LocalDate.of(THE_YEAR, Month.NOVEMBER, 27), // pre-Thanksgiving schedule switch
            LocalDate.of(THE_YEAR, Month.NOVEMBER, 28), // Thanksgiving
            LocalDate.of(THE_YEAR, Month.NOVEMBER, 29) // Thanksgiving
             */
    };
    private static final LocalDate[] TRAVEL = new LocalDate[]{
            LocalDate.of(THE_YEAR, Month.MARCH, 16), // ASPLOS
            LocalDate.of(THE_YEAR, Month.MARCH, 17), // ASPLOS
            LocalDate.of(THE_YEAR, Month.MARCH, 18), // ASPLOS
            LocalDate.of(THE_YEAR, Month.MARCH, 19), // ASPLOS
            LocalDate.of(THE_YEAR, Month.MARCH, 20), // ASPLOS
    };

    private final java.util.List<CalElement> calDays;
    private final Map<LocalDate, CalElement> elemOfDay;
    private static final int DAYS_PER_WEEK = 7;
    private static final Font MY_FONT = new Font("Helvetica Neue", Font.PLAIN, 11);

    EditCourseCalendar() {
        // Create and set up the window
        JFrame frame = new JFrame("Canvas Schedule Editor");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        // java.time.DayOfWeek.getValue returns from 1 (Monday) to 7 (Sunday)
        int dowOffset = FIRST_DAY_OF_CLASSES.getDayOfWeek().getValue() - 1; // [0,6]
        LocalDate firstDayOfCal = FIRST_DAY_OF_CLASSES.minusDays(dowOffset);

        int numDaysInCal = LAST_DAY_OF_CLASSES.getDayOfYear() - firstDayOfCal.getDayOfYear();
        int weeksInCal = (numDaysInCal / DAYS_PER_WEEK) + 1;

        // create main calendar grid
        JPanel calPanel = new JPanel();
        calPanel.setLayout(new GridLayout(weeksInCal+1/*header row*/,DAYS_PER_WEEK));
        DateTimeFormatter dayFormat = DateTimeFormatter.ofPattern("LLL d");

        // add header
        // TODO: reduce height of this row
        for (DayOfWeek dow : DayOfWeek.values()) {
            JLabel header = new JLabel(dow.toString());
            header.setOpaque(true);
            header.setHorizontalAlignment(JLabel.CENTER);
            header.setBackground(Color.RED);
            header.setForeground(Color.WHITE);
            header.setFont(MY_FONT);
            calPanel.add(header);
        }

        calDays = new ArrayList<>(weeksInCal * DAYS_PER_WEEK);
        elemOfDay = new HashMap<>();
        for (int i = 0; i < weeksInCal * DAYS_PER_WEEK; i++) {
            CalElement ce = new CalElement(firstDayOfCal.plusDays(i));
            calDays.add(ce);
            elemOfDay.put(ce.myDate, ce);
        }

        for (CalElement ce : calDays) {
            JPanel dayPanel = new JPanel();
            dayPanel.setLayout(new BoxLayout(dayPanel, BoxLayout.PAGE_AXIS));
            //dayPanel.setBorder(BorderFactory.createLineBorder(Color.BLACK));
            dayPanel.setBackground(Color.WHITE);

            LocalDate date = ce.myDate;
            JLabel dateLabel = new JLabel(date.format(dayFormat));
            dateLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            dateLabel.setFont(MY_FONT);
            boolean isBreak = false;
            if (date.isBefore(FIRST_DAY_OF_CLASSES) || date.isAfter(LAST_DAY_OF_CLASSES)) {
                isBreak = true;
            }
            isBreak |= Arrays.stream(BREAKS).anyMatch(date::isEqual);
            boolean isTravel = Arrays.stream(TRAVEL).anyMatch(date::isEqual);
            if (isBreak) {
                dateLabel.setForeground(Color.GRAY);
                dayPanel.setBackground(Color.lightGray);
            } else if (isTravel) {
                dayPanel.setBackground(new Color(10, 173, 152)); // teal
            }
            dayPanel.add(dateLabel);

            if (!isBreak) {
                JList<String> myList = new JList<>(ce.eventListModel);
                myList.setFont(MY_FONT);
                myList.getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
                myList.setDragEnabled(true);
                myList.setAlignmentX(Component.LEFT_ALIGNMENT);
                myList.setCellRenderer(new MyListCellRenderer());

                MouseListener mouseListener = new MouseAdapter() {
                    public void mouseExited(MouseEvent e) {
                        myList.clearSelection();
                    }
                };
                myList.addMouseListener(mouseListener);

                JScrollPane scrollPane = new JScrollPane(myList);
                scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
                scrollPane.setAlignmentX(Component.LEFT_ALIGNMENT);
                //scrollPane.setBackground(new Color(0,0,0,0));
                dayPanel.add(scrollPane);
            }
            calPanel.add(dayPanel);
        }

        frame.getContentPane().add(calPanel);

        // populate Calendar with Canvas
        try {
            pullCanvasEvents();
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Display the window
        frame.pack();
        frame.setVisible(true);
    }

    private void pullCanvasEvents() throws IOException {
        GenericUrl url;

        url = new GenericUrl(Common.BASE_URL + "calendar_events");
        url.put("type", "event");
        url.put("context_codes[]", "course_" + Common.CourseID());
        //url.put("context_codes[]", "user_4888227");
        url.put("all_events", "true");
        System.out.println("Fetching existing calendar events...");

        List<CalendarEvent> calendarEvents = Common.getAsList(url, CalendarEvent[].class);
        for (CalendarEvent ce : calendarEvents) {
            ce.initTimeFields();
            LocalDate eventStartDate = ce.start_at.toLocalDate();
            CalElement celem = elemOfDay.get(eventStartDate);
            assert celem != null;
            celem.eventListModel.addElement(ce.title);
        }

        List<Assignment> assignments = Common.getAsList("assignments", Assignment[].class);
        for (Assignment assn : assignments) {
            assn.parseTimes();
            if (assn.due_at_string.isEmpty()) {
                System.out.println("empty due date for "+assn.name);
                continue;
            }
            LocalDate dueDate = assn.due_at.toLocalDate();
            if (dueDate.isBefore(FIRST_DAY_OF_CLASSES) || dueDate.isAfter(LAST_DAY_OF_CLASSES)) {
                System.out.println("out-of-semester due date for "+assn.name);
                continue;
            }
            CalElement celem = elemOfDay.get(dueDate);
            assert celem != null;
            celem.eventListModel.addElement(assn.name);
        }
    }

    public static void main(String[] args) throws IOException {
        Common.setup();
        javax.swing.SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                EditCourseCalendar ecc = new EditCourseCalendar();
            }
        });
    }

}

class CalElement {
    final LocalDate myDate;
    final DefaultListModel<String> eventListModel;
    CalElement(LocalDate d) {
        this.myDate = d;
        this.eventListModel = new DefaultListModel<>();
    }
}

class MyListCellRenderer extends JLabel implements ListCellRenderer<Object> {
    // This is the only method defined by ListCellRenderer.
    // We just reconfigure the JLabel each time we're called.

    public Component getListCellRendererComponent(
            JList<?> list,           // the list
            Object value,            // value to display
            int index,               // cell index
            boolean isSelected,      // is the cell selected
            boolean cellHasFocus)    // does the cell have focus
    {
        String s = value.toString();
        setText(s);
        if (isSelected) {
            setBackground(list.getSelectionBackground());
            setForeground(list.getSelectionForeground());
        } else {
            setForeground(Color.black);
            setBackground(Color.white);
            String sl = s.toLowerCase();
            if (sl.contains("exam")) {
                setBackground(new Color(235, 69, 28));
            } else if (sl.contains("cis 371") || sl.contains("cis 501")) {
            } else if (sl.contains("lab") && sl.contains("code")) {
                setBackground(new Color(217, 121, 255));
            } else if (sl.contains("schematic")) {
                setBackground(new Color(150, 249, 162));
            } else if (sl.contains("timing results")) {
                setBackground(new Color(116, 188, 255));
            }
        }
        setEnabled(list.isEnabled());
        setFont(list.getFont());
        setOpaque(true);
        return this;
    }
}