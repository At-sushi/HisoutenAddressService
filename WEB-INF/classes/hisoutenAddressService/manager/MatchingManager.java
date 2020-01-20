package hisoutenAddressService.manager;

import hisoutenAddressService.model.Id;
import hisoutenAddressService.model.tenco.MatchingResult;
import hisoutenAddressService.model.tenco.TencoUser;
import java.util.Calendar;
import java.util.Collection;
import java.util.GregorianCalendar;
import java.util.Iterator;
import java.util.LinkedList;
import org.jgrapht.Graph;
import org.jgrapht.alg.interfaces.MatchingAlgorithm;
import org.jgrapht.alg.matching.EdmondsMaximumCardinalityMatching;
import org.jgrapht.graph.SimpleGraph;

public class MatchingManager {

	private static final int SKIP_MINUTES = 5;
	private final int _playerMaxMinutes;
	private final int _historyClearMinutes;
	private final Collection<TencoUser> _players;
	private final Collection<MatchingResult> _matchingResults;

	public MatchingManager(int playerMaxMinutes, int historyClearMinutes) {
		_playerMaxMinutes = playerMaxMinutes;
		_historyClearMinutes = historyClearMinutes;
		_players = new LinkedList<TencoUser>();
		_matchingResults = new LinkedList<MatchingResult>();
	}

	public synchronized void register(TencoUser player) {
		unregister(player.Id);

		Iterator<TencoUser> iterator = _players.iterator();
		while (iterator.hasNext()) {
			TencoUser user = iterator.next();
			if (user.Id.equals(player.Id)) {
				// データが残っていたら対戦履歴だけ復活させる
				user.copyHistory(player);
				iterator.remove();
				break;
			}
		}
		_players.add(player);
	}

	public synchronized void unregister(Id id) {
		// 自分関連のマッチング結果に登録解除済みをセット
		Iterator<MatchingResult> iterator = _matchingResults.iterator();
		while (iterator.hasNext()) {
			MatchingResult result = iterator.next();
			if (result.Host.Id.equals(id)) {
				result.Host.setUnregistered(true);
			} else if (result.Client.Id.equals(id)) {
				result.Client.setUnregistered(true);
			}

			// 両者共に解除済みならついでに削除しておく
			if (result.Host.isUnregistered() && result.Client.isUnregistered()) {
				iterator.remove();
			}
		}

		// 再登録時に対戦履歴を復活させたいので、プレイヤーは登録解除状態で残しておく
		Iterator<TencoUser> i = _players.iterator();
		while (i.hasNext()) {
			TencoUser user = i.next();
			if (user.Id.equals(id)) {
				user.setUnregistered(true);
			}
		}
	}

	public synchronized void skip(Id player, Id oponent) {
		Iterator<MatchingResult> iterator = _matchingResults.iterator();
		while (iterator.hasNext()) {
			MatchingResult result = iterator.next();

			if (!result.Host.Id.equals(player) && !result.Client.Id.equals(player)) {
				continue;
			}
			if (!result.Host.Id.equals(oponent) && !result.Client.Id.equals(oponent)) {
				continue;
			}

			Calendar removeTime = new GregorianCalendar();
			removeTime.add(Calendar.MINUTE, SKIP_MINUTES);

			Iterator<TencoUser> i = _players.iterator();
			while (i.hasNext()) {
				TencoUser user = i.next();
				if (user.Id.equals(result.Host.Id)) {
					user.addHistory(result.Client.Id, removeTime);
				} else if (user.Id.equals(result.Client.Id)) {
					user.addHistory(result.Host.Id, removeTime);
				}
			}
			iterator.remove();
		}
	}

	public synchronized void addHistory(Id player, Id oponent) {
		Iterator<TencoUser> iterator = _players.iterator();
		while (iterator.hasNext()) {
			TencoUser user = iterator.next();
			if (user.Id.equals(player)) {
				Calendar removeTime = new GregorianCalendar();
				removeTime.add(Calendar.MINUTE, _historyClearMinutes);
				user.addHistory(oponent, removeTime);
			}
		}
	}

	public synchronized void setPrepared(Id player, Id oponent) {
		Iterator<MatchingResult> iterator = _matchingResults.iterator();
		while (iterator.hasNext()) {
			MatchingResult result = iterator.next();
			if (result.Host.Id.equals(player) && result.Client.Id.equals(oponent)) {
				result.setHostPrepared(true);
			} else if (result.Client.Id.equals(player) && result.Host.Id.equals(oponent)) {
				result.setClientPrepared(true);
			}
		}
	}

	public synchronized MatchingResult getMatchingResult(Id player) {
		matching();

		for (MatchingResult result : _matchingResults) {
			if (result.Host.isUnregistered() || result.Client.isUnregistered()) {
				continue;
			}
			if (result.Host.Id.equals(player) || result.Client.Id.equals(player)) {
				return result;
			}
		}
		return null;
	}

	private boolean isMatched(Id player) {
		for (MatchingResult result : _matchingResults) {
			if (result.Host.isUnregistered() || result.Client.isUnregistered()) {
				continue;
			}
			if (result.Host.Id.equals(player) || result.Client.Id.equals(player)) {
				return true;
			}
		}
		return false;
	}

	private synchronized void matching() {
		refreshPlayers();
		refreshMatchingResults();

		for (TencoUser player : _players) {
			player.refreshHistory();
		}

        Graph<TencoUser, MatchingResult> graph = new SimpleGraph<TencoUser, MatchingResult>(MatchingResult.class);
		Collection<TencoUser> clientOnlyUsers = new LinkedList<TencoUser>();
		Collection<TencoUser> hostableUsers = new LinkedList<TencoUser>();
		for (TencoUser player : _players) {
			if (player.isUnregistered()) {
				continue;
			} else if (player.IsHostable) {
				hostableUsers.add(player);
			} else {
				clientOnlyUsers.add(player);
			}
            graph.addVertex(player);
		}

		// まずはクラ専の人から
		for (TencoUser client : clientOnlyUsers) {

			assert !isMatched(client.Id);

			for (TencoUser host : hostableUsers) {

				if (getMatchingResult(host.Id) != null) {
					continue;
				}

				if (client.containsHistory(host.Id) || host.containsHistory(client.Id)) {
					continue;
				}

				if (!client.isMatch(host)) {
					continue;
				}

				MatchingResult result = new MatchingResult(host.copy(), client.copy());
                graph.addEdge(host, client, result);
			}
		}

		// ホスト可の人
		for (TencoUser host : hostableUsers) {

			if (isMatched(host.Id)) {
				continue;
			}

			for (TencoUser client : hostableUsers) {

				if (client.isUnregistered()) {
					continue;
				}

				// 自分 対 自分 は無理
				if (client.Id.equals(host.Id)) {
					continue;
				}

				if (isMatched(client.Id)) {
					continue;
				}

				if (host.containsHistory(client.Id) || client.containsHistory(host.Id)) {
					continue;
				}

				if (!host.isMatch(client)) {
					continue;
				}

				MatchingResult result = new MatchingResult(host.copy(), client.copy());
                graph.addEdge(host, client, result);
			}
		}

        // マッチング処理（Edmondの花分解）
        EdmondsMaximumCardinalityMatching<TencoUser, MatchingResult> matching = new EdmondsMaximumCardinalityMatching<TencoUser, MatchingResult>(graph);
        final MatchingAlgorithm.Matching<TencoUser, MatchingResult> results = matching.getMatching();

        for (MatchingResult result : results)
            _matchingResults.add(result);
	}

	private synchronized void refreshPlayers() {
		Calendar removeTime = new GregorianCalendar();
		removeTime.add(Calendar.MINUTE, -_playerMaxMinutes);

		Iterator<TencoUser> iterator = _players.iterator();
		while (iterator.hasNext()) {
			TencoUser player = iterator.next();
			// 削除する時間が来ていたら
			if (player.getLastUpdate().before(removeTime)) {

				// 関連するマッチング結果を削除してから
				Iterator<MatchingResult> i = _matchingResults.iterator();
				while (i.hasNext()) {
					MatchingResult result = i.next();
					if (result.Host.Id.equals(player.Id) || result.Client.Id.equals(player.Id)) {
						i.remove();
					}
				}

				// 削除する
				iterator.remove();
			}
		}
	}

	private synchronized void refreshMatchingResults() {
		Calendar removeTime = new GregorianCalendar();
		removeTime.add(Calendar.MINUTE, -_playerMaxMinutes);

		Iterator<MatchingResult> iterator = _matchingResults.iterator();
		while (iterator.hasNext()) {
			MatchingResult result = iterator.next();

			// 両者登録解除済みのがあったら削除しておく
			if (result.Host.isUnregistered() && result.Client.isUnregistered()) {
				iterator.remove();
				continue;
			}

			// 削除する時間がきてたら削除しておく
			if (result.getLastUpdate().before(removeTime)) {
				iterator.remove();
			}
		}
	}
}
