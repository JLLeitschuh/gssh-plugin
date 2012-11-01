package com.jerry.ci.plugins.gssh.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;

import org.apache.commons.lang.StringEscapeUtils;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;
import com.jerry.ci.plugins.gssh.GsshPluginException;
import com.jerry.ci.plugins.gssh.GsshUserInfo;
import com.jerry.ci.plugins.gssh.ServerGroup;
import com.jerry.ci.plugins.gssh.Utils;

/**
 * This is Ssh handler , user for handling SSH related event and requirments
 * 
 * @author Jerry Cai
 * 
 */
public class DefaultSshClient extends AbstractSshClient {

	public static final String SSH_PROFILE = "source /etc/profile;source ~/.bash_profile;source ~/.bashrc\n";
	public static final String SSH_BEY_KEY = "congrats , done for your shell!";
	public static final String SSH_BEY = "\necho '" + SSH_BEY_KEY
			+ "'\necho ''";

	private String ip;
	private int port;
	private String username;
	private String password;

	public DefaultSshClient(String ip, int port, String username,
			String password) {
		this.ip = ip;
		this.port = port;
		this.username = username;
		this.password = password;
	}

	public DefaultSshClient(ServerGroup serverGroup, String ip) {
		this.port = serverGroup.getPort();
		this.username = serverGroup.getUsername();
		this.password = serverGroup.getPassword();
		this.ip = ip;
	}

	public static SshClient newInstance(String ip, int port, String username,
			String password) {
		return new DefaultSshClient(ip, port, username, password);
	}

	public static SshClient newInstance(ServerGroup group, String ip) {
		return new DefaultSshClient(group, ip);
	}

	public Session createSession(PrintStream logger) {
		JSch jsch = new JSch();

		Session session = null;
		try {
			session = jsch.getSession(username, ip, port);
			session.setPassword(password);

			UserInfo ui = new GsshUserInfo(password);
			session.setUserInfo(ui);

			java.util.Properties config = new java.util.Properties();
			config.put("StrictHostKeyChecking", "no");
			session.setConfig(config);
			session.setDaemonThread(false);
			session.connect();
		} catch (Exception e) {
			logger.println("create ssh session success with ip=[" + ip
					+ "],port=[" + port + "],username=[" + username
					+ "],password=[" + password + "]");
			e.printStackTrace(logger);
			throw new GsshPluginException(e);
		}
		return session;
	}

	public void uploadFile(PrintStream logger, String fileName,
			InputStream fileContent, String serverLocation) {
		Session session = null;
		ChannelSftp sftp = null;
		OutputStream out = null;
		try {
			session = createSession(logger);
			Channel channel = session.openChannel("sftp");
			channel.setOutputStream(logger, true);
			channel.setExtOutputStream(logger, true);
			channel.connect();
			Thread.sleep(2000);
			sftp = (ChannelSftp) channel;
			sftp.setFilenameEncoding("UTF-8");
			sftp.cd(serverLocation);
			out = sftp.put(fileName, 777);
			Thread.sleep(2000);
			byte[] buffer = new byte[2048];
			int n = -1;
			while ((n = fileContent.read(buffer, 0, 2048)) != -1) {
				out.write(buffer, 0, n);
			}
			out.flush();
			logger.println("upload file [" + fileName + "] to remote ["
					+ serverLocation + "]success");
		} catch (Exception e) {
			logger.println("[GSSH - SFTP]  Exception:" + e.getMessage());
			e.printStackTrace(logger);
			throw new GsshPluginException(e);
		} finally {
			logger.println("[GSSH]-SFTP exit status is " + sftp.getExitStatus());
			if (null != out) {
				try {
					out.close();
				} catch (IOException e) {
				}
			}
			closeSession(session, sftp);
		}
	}

	public void downloadFile(PrintStream logger, String remoteFile,
			String localFolder, String fileName) {
		Session session = null;
		ChannelSftp sftp = null;
		OutputStream out = null;
		try {
			session = createSession(logger);
			Channel channel = session.openChannel("sftp");
			channel.connect();
			Thread.sleep(2000);
			sftp = (ChannelSftp) channel;
			sftp.setFilenameEncoding("UTF-8");
			sftp.get(remoteFile, localFolder + "/" + fileName);
			logger.println("download remote file [" + remoteFile
					+ "] to local [" + localFolder + "] with file name ["
					+ fileName + "]");
		} catch (Exception e) {
			logger.println("[GSSH - SFTP]  Exception:" + e.getMessage());
			e.printStackTrace(logger);
			throw new GsshPluginException(e);
		} finally {
			logger.println("[GSSH]-SFTP exit status is " + sftp.getExitStatus());
			if (null != out) {
				try {
					out.close();
				} catch (IOException e) {
				}
			}
			closeSession(session, sftp);
		}
	}

	public void executeShell(PrintStream logger, String shell) {
		String wrapperShell = wrapperInput(shell);

		Session session = null;
		ChannelShell channel = null;
		InputStream in = null;
		try {
			session = createSession(logger);
			channel = (ChannelShell) session.openChannel("shell");
			channel.setOutputStream(logger, true);
			channel.setExtOutputStream(logger, true);
			channel.setPty(Boolean.FALSE);
			in = Utils.getInputStreamFromString(wrapperShell);
			channel.setInputStream(in, true);
			channel.connect();
			boolean exit = false;
			while (true) {
				while (in.available() > 0) {
					BufferedReader br = new BufferedReader(
							new InputStreamReader(in));
					String output = br.readLine();
					if (output == null) {
						break;
					}
					logger.println(output);
					if (output.indexOf(SSH_BEY_KEY) != -1) {
						exit = true;
						break;
					}
				}
				if (exit) {
					logger.println("##############STOP###############");
					closeSession(session, channel);
					break;
				}
			}
		} catch (Exception e) {
			logger.println("[GSSH]-shell Exception:" + e.getMessage());
			e.printStackTrace(logger);
			throw new GsshPluginException(e);
		} finally {
			logger.println("[GSSH]-shell exit status is "
					+ channel.getExitStatus());
			closeSession(session, channel);
		}
	}

	public void executeCommand(PrintStream logger, String command) {
		String wrapperCommand = wrapperInput(command);
		logger.println("execute below commands:");
		logger.println(wrapperCommand);
		Session session = null;
		ChannelExec channel = null;
		InputStream in = null;
		try {
			session = createSession(logger);
			channel = (ChannelExec) session.openChannel("exec");
			channel.setOutputStream(logger, true);
			channel.setExtOutputStream(logger, true);
			channel.setPty(Boolean.FALSE);
			channel.setCommand(wrapperCommand);
			in = channel.getInputStream();
			channel.connect();
			boolean exit = false;
			while (true) {
				while (in.available() > 0) {
					BufferedReader br = new BufferedReader(
							new InputStreamReader(in));
					String output = br.readLine();
					if (output == null) {
						break;
					}
					logger.println(output);
					if (output.indexOf(SSH_BEY_KEY) != -1) {
						exit = true;
						break;
					}
				}
				if (exit) {
					logger.println("##############STOP###############");
					closeSession(session, channel);
					break;
				}
			}
		} catch (Exception e) {
			logger.println("[GSSH]-cmd Exception:" + e.getMessage());
			e.printStackTrace(logger);
			closeSession(session, channel);
			throw new GsshPluginException(e);

		} finally {
			if (null != in) {
				try {
					in.close();
				} catch (IOException e) {
				}
			}
		}
	}

	public boolean testConnection(PrintStream logger) {
		try {
			Session session = createSession(logger);
			closeSession(session, null);
			return true;
		} catch (Exception e) {
			logger.println("test ssh connection failed !");
			e.printStackTrace(logger);
			return false;
		}
	}

	private void closeSession(Session session, Channel channel) {
		if (channel != null) {
			channel.disconnect();
			channel = null;
		}
		if (session != null) {
			session.disconnect();
			session = null;
		}
	}

	private String wrapperInput(String input) {
		String output = fixIEIssue(input);
		return SSH_PROFILE + output + SSH_BEY;

	}

	/**
	 * this is fix the IE issue that it's input shell /command auto add '<br>
	 * ' if \n
	 * 
	 * @param input
	 * @return
	 */
	private String fixIEIssue(String input) {
		return StringEscapeUtils.unescapeHtml(input);
	}

	public String getIp() {
		return ip;
	}

	public void setIp(String ip) {
		this.ip = ip;
	}

	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public String toString() {
		return "Server Info [" + this.ip + " ," + this.port + ","
				+ this.username + "," + this.password + "]";
	}

}
