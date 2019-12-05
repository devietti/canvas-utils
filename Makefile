
JAVA_CMD=java -ea -cp target/CanvasUtils-1.0-SNAPSHOT-jar-with-dependencies.jar

package:
#   tests are time-consuming, so don't run them by default
	mvn -Dmaven.test.skip=true package

cal: package
	$(JAVA_CMD) canvas.SetCourseCalendar

timingresults: package
	$(JAVA_CMD) canvas.DownloadQuizResponses timingresults.csv

groupdb: package
	$(JAVA_CMD) canvas.CreateGroupDB groups.json ""

demo: package
	$(JAVA_CMD) canvas.UploadDemoGrades

joe: package
	$(JAVA_CMD) canvas.LockerCombos

ship: package
	scp target/CanvasUtils-1.0-SNAPSHOT-jar-with-dependencies.jar cis501@eniac.seas.upenn.edu:



# DEPRECATED

roster: package
	$(JAVA_CMD) canvas.CreateRosterForGradescope roster-for-gs.csv

grades: package
	$(JAVA_CMD) canvas.UploadAssignmentGrades hw7-grades.csv hw7-test-output

late: package
	$(JAVA_CMD) canvas.archived.DockLateSubmissions

exam-grades: package
	$(JAVA_CMD) canvas.UploadGradescopeGrades cis371_spring2018_final_exam_scores.csv

assndb: package
	$(JAVA_CMD) canvas.CreateAssignmentDB assignments.json

reconcile: package
	$(JAVA_CMD) canvas.archived.GroupGradeReconciler ""

testlti:
	mvn test

