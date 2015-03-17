/**
 * Copyright (c) 2010-2015, openHAB.org and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.souliss.internal.network.udp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.ArrayList;
import java.util.Date;

import org.openhab.binding.souliss.internal.network.typicals.SoulissGenericTypical;
import org.openhab.binding.souliss.internal.network.typicals.SoulissNetworkParameter;
import org.openhab.binding.souliss.internal.network.typicals.SoulissTypicals;
import org.openhab.binding.souliss.internal.network.typicals.StateTraslator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class provide to take packet, and send it to regular interval to Souliss
 * Network
 * 
 * @author Tonino Fazio
 * @since 1.7.0
 */
public class SendDispatcherThread extends Thread {

	static ArrayList<SocketAndPacket> packetsList = new ArrayList<SocketAndPacket>();
	protected boolean bExit = false;
	static int iDelay = 0; // equal to 0 if array is empty
	int SEND_DELAY;
	int SEND_MIN_DELAY;
	static boolean bPopSuspend = false;
	private static Logger LOGGER = LoggerFactory
			.getLogger(SendDispatcherThread.class);
	private static SoulissTypicals soulissTypicalsRecipients;

	public SendDispatcherThread(SoulissTypicals soulissTypicalsRecip,
			int SEND_DELAY, int SEND_MIN_DELAY) throws IOException {
		this("SendDispatcher");
		this.SEND_DELAY = SEND_DELAY;
		this.SEND_MIN_DELAY = SEND_MIN_DELAY;
		LOGGER.info("Start SendDispatcherThread");
		soulissTypicalsRecipients = soulissTypicalsRecip;
	}

	public SendDispatcherThread(String name) {
		super(name);
	}

	/**
	 * Put packet to send in ArrayList PacketList
	 */
	public synchronized static void put(DatagramSocket socket,
			DatagramPacket packetToPUT) {

		bPopSuspend = true;
		boolean bPacchettoGestito = false;
		// estraggo il nodo indirizzato dal pacchetto in ingresso
		// restituisce -1 se il node non è del tipo Souliss_UDP_function_force
		int node = getNode(packetToPUT);
		LOGGER.debug("Push");
		if (packetsList.size() == 0 || node < 0) {
			bPacchettoGestito = false;
		} else {
			// OTTIMIZZATORE
			// scansione lista paccetti da inviare
			for (int i = 0; i < packetsList.size(); i++) {
				if (node >= 0 && getNode(packetsList.get(i).packet) == node
						&& !packetsList.get(i).isSent()) {
					// frame per lo stesso nodo già presente in lista
					LOGGER.debug("Frame UPD per nodo "
							+ node
							+ " già presente il lista. Esecuzione ottimizzazione.");
					bPacchettoGestito = true;
					// se il pacchetto da inserire è più corto (o uguale) di
					// quello in lista allora sovrascrivo i byte del pacchetto
					// presente in lista
					if (packetToPUT.getData().length <= packetsList.get(i).packet
							.getData().length) {
						// scorre i byte di comando e se il byte è diverso da
						// zero sovrascrive il byte presente nel pacchetto in
						// lista
						LOGGER.debug("Optimizer.             Packet to push: "
								+ MaCacoToString(packetToPUT.getData()));
						LOGGER.debug("Optimizer.             Previous frame: "
								+ MaCacoToString(packetsList.get(i).packet
										.getData()));
						// i valori dei tipici partono dal byte 12 in poi
						for (int j = 12; j < packetToPUT.getData().length; j++) {
							// se il j-esimo byte è diverso da zero allora lo
							// sovrascrivo al byte del pacchetto già presente
							if (packetToPUT.getData()[j] != 0) {
								packetsList.get(i).packet.getData()[j] = packetToPUT
										.getData()[j];
							}
						}
						LOGGER.debug("Optimizer. Previous frame modified to: "
								+ MaCacoToString(packetsList.get(i).packet
										.getData()));
					} else {
						// se il pacchetto da inserire è più lungo di quello in
						// lista allora sovrascrivo i byte del pacchetto da
						// inserire, poi elimino quello in lista ed inserisco
						// quello nuovo
						if (packetToPUT.getData().length > packetsList.get(i).packet
								.getData().length) {
							for (int j = 12; j < packetsList.get(i).packet
									.getData().length; j++) {
								// se il j-esimo byte è diverso da zero allora
								// lo sovrascrivo al byte del pacchetto gi�
								// presente
								if (packetsList.get(i).packet.getData()[j] != 0) {
									if (packetToPUT.getData()[j] == 0) {
										// sovrascrive i byte dell'ultimo frame
										// soltanto se il byte è uguale a zero.
										// Se è diverso da zero l'ultimo frame
										// ha la precedenza e deve sovrascrivere
										packetToPUT.getData()[j] = packetsList
												.get(i).packet.getData()[j];
									}
								}
							}
							// rimuove il pacchetto
							LOGGER.debug("Optimizer. Remove frame: "
									+ MaCacoToString(packetsList.get(i).packet
											.getData()));
							packetsList.remove(i);
							// inserisce il nuovo
							LOGGER.debug("Optimizer.    Add frame: "
									+ MaCacoToString(packetToPUT.getData()));
							packetsList.add(new SocketAndPacket(socket,
									packetToPUT));
						}
					}
				}
			}
		}

		if (!bPacchettoGestito) {
			LOGGER.debug("Add frame: " + MaCacoToString(packetToPUT.getData()));
			packetsList.add(new SocketAndPacket(socket, packetToPUT));
		}
		bPopSuspend = false;
	}

	/**
	 * Get node number from packet
	 */
	private static int getNode(DatagramPacket packet) {
		// 7 è il byte del frame VNet al quale trovo il codice comando
		// 10 è il byte del frame VNet al quale trovo l'ID del nodo
		if (packet.getData()[7] == (byte) ConstantsUDP.Souliss_UDP_function_force) {
			return packet.getData()[10];
		}
		return -1;
	}

	long t, t_prec = 0;

	/**
	 * Pop SocketAndPacket from ArrayList PacketList
	 */
	private synchronized SocketAndPacket pop() {
		synchronized (this) {
			// non esegue il pop se bPopSuspend=true
			// bPopSuspend è impostato dal metodo put
			if (!bPopSuspend) {
				t = System.currentTimeMillis();
				// riporta l'intervallo al minimo solo se:
				// - la lista è minore o uguale a 1;
				// - se è trascorso il tempo SEND_DELAY.

				if (packetsList.size() <= 1)
					iDelay = SEND_MIN_DELAY;
				else
					iDelay = SEND_DELAY;

				int iPacket = 0;
				boolean bFlagWhile = true;
				// scarta i pacchetti già inviati
				while (!(iPacket >= packetsList.size()) && bFlagWhile) {
					if (packetsList.get(iPacket).sent) {
						iPacket++;
					} else
						bFlagWhile = false;
				}

				boolean tFlag = (t - t_prec) >= SEND_DELAY;
				// se siamo arrivati alla fine della lista e quindi tutti i
				// pacchetti sono già stati inviati allora pongo anche il tFlag
				// a false (come se il timeout non fosse ancora trascorso)
				if (iPacket >= packetsList.size())
					tFlag = false;

				if (packetsList.size() > 0 && tFlag) {
					t_prec = System.currentTimeMillis();
					// SocketAndPacket sp = packetsList.remove(0);

					// estratto il primo elemento della lista
					SocketAndPacket sp = packetsList.get(iPacket);

					// GESTIONE PACCHETTO: eliminato dalla lista oppure
					// contrassegnato come inviato se è un FORCE
					if (packetsList.get(iPacket).packet.getData()[7] == (byte) ConstantsUDP.Souliss_UDP_function_force) {
						// flag inviato a true
						packetsList.get(iPacket).setSent(true);
						// imposto time
						packetsList.get(iPacket).setTime(
								System.currentTimeMillis());
					} else {
						packetsList.remove(iPacket);
					}

					LOGGER.debug("POP: " + packetsList.size()
							+ " packets in memory");
					if (LOGGER.isDebugEnabled()) {
						int iPacketSentCounter = 0;
						int i = 0;
						while (!(i >= packetsList.size())) {
							if (packetsList.get(i).sent) {
								iPacketSentCounter++;
							}
							i++;
						}
						LOGGER.debug("POP: " + (iPacketSentCounter)
								+ " force frame sent");
					}

					LOGGER.debug("Pop frame "
							+ MaCacoToString(sp.packet.getData())
							+ " - Delay for 'SendDispatcherThread' setted to "
							+ iDelay + " mills.");
					return sp;
				}
			}
		}
		return null;
	}

	/**
	 * Sleep for iDelay Get and send packet
	 */
	public void run() {

		while (!bExit) {

			try {
				Thread.sleep(iDelay);
				SocketAndPacket sp = pop();
				if (sp != null) {
					LOGGER.debug("SendDispatcherThread - Functional Code 0x"
							+ Integer.toHexString(sp.packet.getData()[7])
							+ " - Packet: "
							+ MaCacoToString(sp.packet.getData())
							+ " - Elementi rimanenti in lista: "
							+ packetsList.size());
					sp.socket.send(sp.packet);
				}
				// confronta gli stati in memoria con i frame inviati. Se
				// corrispondono cancella il frame dalla lista inviati
				SendDispatcherThread.safeSendCheck();

			} catch (IOException | InterruptedException e) {
				e.printStackTrace();
				LOGGER.error(e.getMessage());
			} catch (Exception e) {
				e.printStackTrace();
				LOGGER.error(e.getMessage());
			}
		}
	}

	private static String MaCacoToString(byte[] frame2) {
		byte[] frame = frame2.clone();
		StringBuilder sb = new StringBuilder();
		sb.append("HEX: [");
		for (byte b : frame) {
			sb.append(String.format("%02X ", b));
		}
		sb.append("]");
		return sb.toString();
	}

	/**
	 * check frame updates with packetList, where flag "sent" is true. If all
	 * commands was executed there delete packet in list. confronta gli
	 * aggiornamenti ricevuti con i frame inviati. Se corrispondono cancella il
	 * frame nella lista inviati .
	 */
	public static void safeSendCheck() {
		// short sVal = getByteAtSlot(macacoFrame, slot);
		// scansione lista paccetti inviati
		for (int i = 0; i < packetsList.size(); i++) {
			if (packetsList.get(i).isSent()) {
				int node = getNode(packetsList.get(i).packet);
				int iSlot = 0;
				for (int j = 12; j < packetsList.get(i).packet.getData().length; j++) {
					// controllo lo slot solo se il comando è diveerso da ZERO
					if (packetsList.get(i).packet.getData()[j] != 0) {
						// recupero tipico dalla memoria
						SoulissGenericTypical typ = soulissTypicalsRecipients
								.getTypicalFromAddress(node, iSlot, 0);

						// traduce il comando inviato con lo stato previsto e
						// poi fa il confronto con lo stato attuale
						if(LOGGER.isDebugEnabled()){
						String s1 = String.valueOf((int) typ.getState());
						String sStateMemoria = s1.length() < 2 ? "0x0"
								+ s1.toUpperCase() : "0x" + s1.toUpperCase();
								
						String sCmd=Integer.toHexString(packetsList.get(i).packet.getData()[j]);
						sCmd = sCmd.length() < 2 ? "0x0"
								+ sCmd.toUpperCase() : "0x" + sCmd.toUpperCase();
						LOGGER.debug("Compare. Node: " + node + " Slot: "+ iSlot +  " Typical: "
								+ Integer.toHexString(typ.getType())
								+ " Command: "
								+ sCmd
								+ " EXPECTED: "
								+ expectedState(typ.getType(),
										packetsList.get(i).packet.getData()[j])
								+ " - IN MEMORY: " + sStateMemoria);
						}
						
						if (checkExpectedState(
								(int) typ.getState(),
								expectedState(typ.getType(),
										packetsList.get(i).packet.getData()[j]))) {
							// se il valore del tipico coincide con il valore
							// trasmesso allora pongo il byte a zero.
							// quando tutti i byte saranno uguale a zero allora
							// si
							// cancella il frame
							packetsList.get(i).packet.getData()[j] = 0;
							LOGGER.debug("T" + Integer.toHexString(typ.getType()) + " Node: " + node + " Slot: " + iSlot
									+ " - OK Expected State");
						}
					}
					iSlot++;
				}
				if (checkAllsSlotZero(packetsList.get(i).packet)) {
					LOGGER.debug("Command packet executed - Removed");
					packetsList.remove(i);
				} else {
					// se il frame non è uguale a zero controllo il TIMEOUT e se
					// è scaduto allora pongo il flag SENT a false
					long t=System.currentTimeMillis();
					if (SoulissNetworkParameter.SECURE_SEND_TIMEOUT_TO_REQUEUE < t - packetsList.get(i).getTime()) {
						if (SoulissNetworkParameter.SECURE_SEND_TIMEOUT_TO_REMOVE_PACKET < t - packetsList.get(i).getTime()) {
							LOGGER.info("Packet Execution timeout - Removed");
							packetsList.remove(i);
						} else {
							LOGGER.info("Packet Execution timeout - Requeued");
							packetsList.get(i).setSent(false);
						}
					}
				}
			}
		}
	}

	private static boolean checkExpectedState(int state, String expectedState) {
		String s1 = String.valueOf(state);
		String sState = s1.length() < 2 ? "0x0" + s1.toUpperCase() : "0x"
				+ s1.toUpperCase();
		return sState.equals(expectedState);
	}

	private static String expectedState(short soulissType, byte command) {
		return StateTraslator.translateCommandsToExpectedStates(soulissType,
				command);
	}

	private static boolean checkAllsSlotZero(DatagramPacket packet) {
		boolean bflag = true;
		for (int j = 12; j < packet.getData().length; j++) {
			if (!(packet.getData()[j] == 0))
				bflag = false;
		}
		return bflag;
	}
}
