package org.klco.sling.votecheck;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.mail.Message;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.klco.email2html.Hook;
import org.klco.email2html.models.Email2HTMLConfiguration;
import org.klco.email2html.models.EmailMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VoteCheckHook implements Hook {

	private static final Pattern RELEASE_COMMAND = Pattern
			.compile("sh check_staged_release.sh (\\d)+ /tmp/sling-staging");

	private static final String SLING_PROJECT_PATH = "/opt/dev/sling";

	private static final Logger log = LoggerFactory
			.getLogger(VoteCheckHook.class);

	private Email2HTMLConfiguration config;

	private String bin = "";

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
		Matcher m = RELEASE_COMMAND.matcher(fullMessage);
		m.find();
		String id = filterDigits(m.group());
		log.debug("Found ID {}", id);

		try {
			log.debug("Checking to see if repo exists...");

			URL url = new URL(
					"https://repository.apache.org/content/repositories/orgapachesling-"
							+ id);
			HttpURLConnection http = (HttpURLConnection) url.openConnection();
			int statusCode = http.getResponseCode();

			StringBuilder result = new StringBuilder();

			if (statusCode != 200) {
				log.warn("Unable to find release repo {}, error code {}",
						new Object[] { url, http.getResponseMessage() });
				params.put("status", "warn");
				params.put("reason", "Unable to find release repository");
				params.put("validationResult", "Unable to find release repo "
						+ url + ", error code " + http.getResponseCode() + ":"
						+ http.getResponseMessage());

			} else {
				log.debug("Executing commands...");

				call("sh " + SLING_PROJECT_PATH + "/check_staged_release.sh "
						+ id + " /tmp/sling-staging", result);

				String validationResult = result.toString();
				if (validationResult.contains("BAD!!")) {
					params.put("status", "bad");
					params.put("reason", "GPG or checksum validation failed");
				} else {
					params.put("status", "good");
					params.put("reason", "All checks passed");
				}
				params.put("validationResult", validationResult);

				log.debug("Copying validation output and adding as attachment...");
				File source = new File("/tmp/sling-staging/" + id);
				File target = new File(config.getOutputDir() + File.separator
						+ config.getImagesSubDir() + File.separator + id);
				List<File> files = this.copyFiles(source, target);
				List<String> filePaths = new ArrayList<String>();
				for (File file : files) {
					filePaths.add(file.getAbsolutePath().substring(
							config.getOutputDir().length()));
				}
				params.put("attachments", filePaths);
			}

			fullMessage = fullMessage.replaceAll("(\r\n|\n)", "<br/>");
			fullMessage = fullMessage.replace("-", "&ndash;");
			params.put("htmlMessage", fullMessage);
		} catch (IOException e) {
			log.error("IOException updating message", e);
		}

	}

	public List<File> copyFiles(File source, File target) throws IOException {
		List<File> files = new ArrayList<File>();

		if (source != null && source.exists() && source.isDirectory()) {
			for (File srcFile : source.listFiles()) {
				if (!target.exists()) {
					target.mkdirs();
				}
				if (srcFile.isDirectory()) {
					files.addAll(copyFiles(srcFile,
							new File(target.getAbsolutePath() + File.separator
									+ srcFile.getName())));
				} else {
					FileUtils.copyFileToDirectory(srcFile, target);
				}
			}
		}
		return files;
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

		// Working around an issue with testing OSX where GPG and WGET aren't in
		// /bin
		if (new File("/usr/local/bin/wget").exists()) {
			bin = "/usr/local/bin/";
		}

		StringBuilder result = new StringBuilder();
		call(bin
				+ "wget https://people.apache.org/keys/group/sling.asc -O /tmp/sling.asc",
				result);
		call(bin + "gpg --import /tmp/sling.asc", result);
		log.debug("Init result {}", result.toString());
	}

}
