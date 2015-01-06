package org.klco.sling.votecheck;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.mail.Message;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.text.StrSubstitutor;
import org.klco.email2html.Hook;
import org.klco.email2html.models.Email2HTMLConfiguration;
import org.klco.email2html.models.EmailMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VoteCheckHook implements Hook {

	private static final Pattern RELEASE_COMMAND = Pattern
			.compile("sh check_staged_release.sh (\\d)+ /tmp/sling-staging");

	private static final Pattern RELEASE_REPO = Pattern
			.compile("https://repository.apache.org/content/repositories/orgapachesling-+(\\d)+/?");

	private static final String SLING_PROJECT_PATH = "/opt/dev/sling";

	private static final Logger log = LoggerFactory
			.getLogger(VoteCheckHook.class);

	private Email2HTMLConfiguration config;

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.klco.email2html.Hook#afterComplete()
	 */
	public void afterComplete() {
		// do nothing
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.klco.email2html.Hook#afterRead(javax.mail.Message,
	 * org.klco.email2html.models.EmailMessage)
	 */
	public boolean afterRead(Message sourceMessage, EmailMessage message) {
		String subject = message.getSubject();
		Matcher m = RELEASE_COMMAND.matcher(message.getFullMessage());
		if (!subject.contains("[RELEASE]") && !subject.contains("RELEASE]")
				&& !subject.toLowerCase().contains("re:") && m.find()) {
			return true;
		}
		return false;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.klco.email2html.Hook#afterWrite(org.klco.email2html.models.EmailMessage
	 * , java.util.Map, java.io.File)
	 */
	public void afterWrite(EmailMessage message, Map<String, Object> params,
			File file) {
		// do nothing
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.klco.email2html.Hook#beforeWrite(org.klco.email2html.models.EmailMessage
	 * , java.util.Map)
	 */
	public void beforeWrite(EmailMessage message, Map<String, Object> params) {
		log.trace("beforeWrite");
		String fullMessage = message.getFullMessage();
		String subject = message.getSubject();
		Map<String, String> properties = new HashMap<String, String>();
		Matcher m = RELEASE_COMMAND.matcher(fullMessage);
		m.find();
		String id = filterDigits(m.group());
		log.debug("Found ID {}", id);

		try {
			log.debug("Checking to see if repo exists...");
			Matcher urlMatcher = RELEASE_REPO.matcher(fullMessage);
			int statusCode = 200;
			if (urlMatcher.find()) {
				String urlStr = urlMatcher.group();
				URL url = new URL(urlStr);
				HttpURLConnection http = (HttpURLConnection) url
						.openConnection();
				statusCode = http.getResponseCode();
				if (statusCode != 200) {
					log.warn("Unable to find release repo {}, error code {}",
							new Object[] { urlStr, http.getResponseMessage() });
					properties.put("validationResultClass", "warn");
					properties.put(
							"validationResult",
							"Unable to find release repo " + urlStr
									+ ", error code "
									+ http.getResponseMessage());
					return;
				}
			} else {
				log.warn("No repo url found in message {}", fullMessage);
			}

			StringBuilder result = new StringBuilder();

			if (statusCode == 200) {
				log.debug("Executing commands...");

				call("sh " + SLING_PROJECT_PATH + "/check_staged_release.sh "
						+ id + " /tmp/sling-staging", result);

				String validationResult = result.toString();
				properties.put("validationResultClass",
						(validationResult.contains("BAD!!") ? "bad" : "good"));
				properties.put("validationResult", validationResult);

				log.debug("Copying validation output and adding as attachment...");
				File folder = new File("/tmp/sling-staging/" + id);
				if (folder != null && folder.exists() && folder.isDirectory()) {
					for (File srcFile : folder.listFiles()) {
						File destDir = new File(config.getOutputDir()
								+ File.separator + config.getImagesSubDir()
								+ File.separator + id);
						if (!destDir.exists()) {
							destDir.mkdirs();
						}
						FileUtils.copyFileToDirectory(srcFile, destDir);
						message.getAttachments().add(
								new File(destDir.getAbsolutePath()
										+ File.separator + srcFile.getName()));
					}
				}
			}

			log.debug("Updating message...");
			String messageTemplate = IOUtils.toString(getClass()
					.getResourceAsStream("/message.html"));
			properties.put("subject", subject);

			StrSubstitutor sub = new StrSubstitutor(properties);
			message.setMessage(sub.replace(messageTemplate));
		} catch (IOException e) {
			log.error("IOException updating message", e);
		}

	}

	/**
	 * Returns a substring containing only the numeric values from the provided
	 * string.
	 * 
	 * @param str
	 *            the string to filter
	 * @return a string containing only the digits from the provided string
	 */
	private String filterDigits(String str) {
		StringBuilder id = new StringBuilder();
		for (char c : str.toCharArray()) {
			if (Character.isDigit(c)) {
				id.append(c);
			}
		}
		return id.toString();
	}

	/**
	 * Executes a command call and adds the stream to the result.
	 * 
	 * @param cmd
	 *            the command to execute
	 * @param result
	 *            the result to append the output to
	 */
	private void call(String cmd, StringBuilder result) {
		Process p = null;
		BufferedReader reader = null;
		try {
			p = Runtime.getRuntime().exec(cmd);
			p.waitFor();

			reader = new BufferedReader(new InputStreamReader(
					p.getInputStream()));
			result.append(IOUtils.toString(reader));
			IOUtils.closeQuietly(reader);

			reader = new BufferedReader(new InputStreamReader(
					p.getErrorStream()));
			result.append(IOUtils.toString(reader));

		} catch (IOException e) {
			log.error("IOException calling command " + cmd, e);
			result.append("IOException calling command '" + cmd + "': " + e);
		} catch (InterruptedException e) {
			log.error("Thread Interrupt calling command " + cmd, e);
			result.append("Thread Interrupt calling command '" + cmd + "': "
					+ e);
		} finally {
			IOUtils.closeQuietly(reader);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.klco.email2html.Hook#init(org.klco.email2html.models.
	 * Email2HTMLConfiguration)
	 */
	public void init(Email2HTMLConfiguration config) {
		this.config = config;

		StringBuilder result = new StringBuilder();
		call("/usr/local/bin/wget https://people.apache.org/keys/group/sling.asc -O /tmp/sling.asc",
				result);
		call("/usr/local/bin/gpg --import /tmp/sling.asc", result);
		log.debug("Init result {}", result.toString());
	}

}
