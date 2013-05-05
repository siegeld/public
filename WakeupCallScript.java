package com.siegel.asterisk;

import com.siegel.util.SiteProperties;
import java.util.Date;
import java.util.Collection;
import java.text.ParseException;
import java.io.IOException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.asteriskjava.fastagi.AgiChannel;
import org.asteriskjava.fastagi.AgiException;
import org.asteriskjava.fastagi.AgiRequest;
import org.asteriskjava.fastagi.BaseAgiScript;
import org.hibernate.SessionFactory;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.HibernateException;
import org.hibernate.cfg.Configuration;

public class WakeupCallScript extends BaseAgiScript {
    static Log log = LogFactory.getLog(WakeupCallScript.class.getName());
    private Session session = null;
    private AgiChannel channel = null;
    private Say say;
    private Weather weather;
    private String locationName;
    private static final String reminders[] = {
	"Time to get up!",
	"Don't sleep too long!",
	"Are you too happy in bed to get up?",
	"You'll have a great day today, time to get going!",
	"How long are you going to listen to this wakeup call?",
	"Maybe you forgot to hangup this phone call?"
    };

    public void service(AgiRequest request, AgiChannel channel)
            throws AgiException
    {
	this.channel = channel;
	say = new Say(channel);

	SessionFactory factory = new Configuration()
	    .setProperties(SiteProperties.getProperties())
	    .configure()
	    .buildSessionFactory();
	session = factory.openSession();

	String site = SiteProperties.getProperties().getProperty("weather.site");
	if (site == null)
	    site = "SCA";
	weather = new Weather(site);

	locationName = SiteProperties.getProperties().getProperty("weather.location");

	String type = request.getParameter("type");
	if (type == null || type.length() == 0)
	    return;

	log.info("Wakeup Call Service Entered: " + type);

	if (type.equals("setup")) {
	    setupCall(request);
	} else if (type.equals("handle")) {
	    handleCall(request);
	} else if (type.equals("list")) {
	    listCalls(request);
	} else if (type.equals("cancel")) {
	    cancelCalls(request);
	} else if (type.equals("sleep")) {
	    sleepCall(request);
	}

	session.close();
    }

    private String getTime() 
	throws AgiException {
	String time = null;
	do {
	    int hours = say.getInt("Enter hour", 1, 12);
	    int minutes = say.getInt("Enter minutes", 0, 59);
	    String ap[] = {"AM", "PM"};
	    int apOffset = say.getOption(ap);
	    time = String.format("%d:%02d %s", hours, minutes, ap[apOffset]);
	} while (!say.confirm(time));
	log.info("confirmed time");
	return time;
    }

    private void cancelCalls(AgiRequest request) 
	throws AgiException {
        answer();
	
	String extension = request.getParameter("exten");
	if (extension == null || extension.length() == 0) {
	    say.string("No extension specified, good bye");
	    hangup();
	    return;
	}

	Collection<WakeupCall> calls = WakeupCall.getWakeupCalls(session, extension);
	if (calls.size() == 0) {
	    say.string("No wake up calls are scheduled");
	} else {
	    for (WakeupCall call : calls) {
		call.cancel(session);
	    }
	    say.string("All scheduled calls have been canceled.  Goodbye.");
	}

	hangup();
    }

    private void listCalls(AgiRequest request) 
	throws AgiException {
        answer();
	
	String extension = request.getParameter("exten");
	if (extension == null || extension.length() == 0) {
	    say.string("No extension specified, good bye");
	    hangup();
	    return;
	}

	Collection<WakeupCall> calls = WakeupCall.getWakeupCalls(session, extension);
	if (calls.size() == 0) {
	    say.string("No wake up calls are scheduled");
	} else {
	    say.string(calls.size() + " wake up calls are scheduled");
	    int i = 1;
	    for (WakeupCall call : calls) {
		say.string("Call " + i++);
		say.sayDateTime(new Date(call.getNextRunTime().getTime()));
		if (call.getSchedule() == WakeupCall.SCHEDULE_DAILY) {
		    say.string("scheduled to run every day");
		} else if (call.getSchedule() == WakeupCall.SCHEDULE_WEEKDAYS) {
		    say.string("scheduled to run every weekday");
		}
	    }
	}

	say.string("Goodbye");

	hangup();
    }

    private void sleepCall(AgiRequest request) 
	throws AgiException {
        answer();
	
	say.string("Welcome to the wake up call service!");

	String extension = request.getParameter("exten");
	if (extension == null || extension.length() == 0) {
	    say.string("No extension specified, good bye");
	    hangup();
	    return;
	}

	int minutes = say.getInt("Enter the number of minutes to sleep");
	
	WakeupCall call = new WakeupCall();
	call.setExtension(extension);
	call.scheduleInMinutes(minutes);

	try {
	    Transaction transaction = session.beginTransaction();
	    call.save(session);
	    transaction.commit();
	} catch (HibernateException e) {
	    log.warn(e.getMessage());
	    return;
	}

	say.string("Thank you.  Your wake up call has been scheduled.");
    }

    private void setupCall(AgiRequest request) 
	throws AgiException {
        answer();
	
	say.string("Welcome to the wake up call service!");

	String extension = request.getParameter("exten");
	if (extension == null || extension.length() == 0) {
	    say.string("No extension specified, good bye");
	    hangup();
	    return;
	}

	String time = null;
	int option;
	do {
	    int hours = say.getInt("Enter hour", 1, 12);
	    int minutes = say.getInt("Enter minutes", 0, 59);
	    String ap[] = {"AM", "PM"};
	    int apOffset = say.getOption(ap);
	    time = String.format("%d:%02d %s", hours, minutes, ap[apOffset]);
	    String text = time + " Press 1 to save, 2 to schedule every weekday, 3 to schedule every day, " +
		"or 4 to try again";
	    option = say.getInt(text, 1, 4);
	} while (option == 4);
	
	char schedule;
	switch (option) {
	case 1:
	    schedule = WakeupCall.SCHEDULE_ONESHOT;
	    break;
	case 2:
	    schedule = WakeupCall.SCHEDULE_WEEKDAYS;
	    break;
	case 3:
	    schedule = WakeupCall.SCHEDULE_DAILY;
	    break;
	default:
	    schedule = WakeupCall.SCHEDULE_ONESHOT;
	    break;
	}

	WakeupCall call = null;
	try {
	    call = new WakeupCall(extension, time, schedule);
	} catch (ParseException e) {
	    log.warn(e.getMessage());
	    return;
	}

	try {
	    Transaction transaction = session.beginTransaction();
	    call.save(session);
	    transaction.commit();
	} catch (HibernateException e) {
	    log.warn(e.getMessage());
	    return;
	}

	if (schedule == WakeupCall.SCHEDULE_ONESHOT) {
	    say.string("Thank you.  Your wake up call is scheduled for " + call.getNextRunTimePrettyNoSchedule());
	} else if (schedule == WakeupCall.SCHEDULE_WEEKDAYS) {
	    say.string("Thank you.  Your wake up call is next scheduled for " + call.getNextRunTimePrettyNoSchedule() +
		       " and will run every weekday");
	} else if (schedule == WakeupCall.SCHEDULE_DAILY) {
	    say.string("Thank you.  Your wake up call is next scheduled for " + call.getNextRunTimePrettyNoSchedule() +
		       " and will run every day");
	}

	say.string("Goodbye");
    }

    private String getWeatherReport() {
	StringBuffer sb = new StringBuffer();
	try {
	    float t = Float.parseFloat(weather.getTemperature());
	    if (t > 85) {
		sb.append("It's really a hot day.");
	    } else if (t > 68) {
		sb.append("It's nice and warm outside.");
	    } else if (t > 45) {
		sb.append("It's pretty cool outside, but not bad.");
	    } else if (t > 30) {
		sb.append("It's cold outside, dress warm.");
	    } else if (t > 20) {
		sb.append("It's really cold outside.");
	    } else {
		sb.append("It's super cold outside, are you sure you want to get out of bed?");
	    }
	} catch (NumberFormatException e) { }
	sb.append(" ");
	sb.append("The weather in " + locationName + " is " + weather.getWeather() + ". ")
	    .append("The current temperature is ")
	    .append(weather.getTemperature())
	    .append(" degrees with a humidity of ")
	    .append(weather.getHumidity())
	    .append(" percent.  The wind is ")
	    .append(weather.getWind()).append(".")
	    .append(" Visibility is ")
	    .append(weather.getVisability())
	    .append(" miles.");
	return sb.toString();
    }

    private void runScript(String extension) {
	String scriptDir = SiteProperties.getProperties().getProperty("wakeupcall.script.directory");
	if (scriptDir != null) {
	    String[] cmd = { scriptDir + "/" + extension };
	    try {
		Runtime.getRuntime().exec(cmd);
	    } catch (IOException e) {
		log.warn("script execution for " + extension + " failed", e);
	    }
	}
    }

    private void handleCall(AgiRequest request) 
	throws AgiException {
	String extension = request.getParameter("exten");
	if (extension == null || extension.length() == 0)
	    extension = null;

	String weather = getWeatherReport();

        answer();

	say.string("This is your wake up call");
	if (extension != null) {
	    runScript(extension);
	    say.string("This call is for extension " + extension);
	}

	int counter = 0;
	for (int i = 0; i < 4*10; i++) {
	    if (counter == 0) {
		say.string("The time is now ");
		sayDateTime(new Date().getTime() / 1000, null, "IMP", "US/Eastern");
		say.string(weather);
	    }
	    playMusicOnHold();
	    try {
		Thread.sleep(15 * 1000);
	    } catch (InterruptedException e) {
	    }
	    stopMusicOnHold();
	    say.string(reminders[counter++]);
	    if (counter >= reminders.length)
		counter = 0;
	}
    }
}
